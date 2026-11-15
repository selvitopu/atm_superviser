package io.github.selvitopu.atmsup.supervisor.polling;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.supervisor.board.BoardEvents.BoardReady;
import io.github.selvitopu.atmsup.supervisor.board.BoardEvents.RepollRequested;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.PollFailed;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.PollSucceeded;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.ProbeFailed;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.ProbeSucceeded;
import io.github.selvitopu.atmsup.supervisor.board.BoardInputSink;
import io.github.selvitopu.atmsup.supervisor.board.GenerationSource;
import io.github.selvitopu.atmsup.supervisor.polling.PollDispatcher.Priority;
import io.github.selvitopu.atmsup.supervisor.snmp.Observation;
import io.github.selvitopu.atmsup.supervisor.snmp.ObservationMapper;
import io.github.selvitopu.atmsup.supervisor.snmp.PollPlan;
import io.github.selvitopu.atmsup.supervisor.snmp.SnmpClient;
import io.github.selvitopu.atmsup.supervisor.snmp.SnmpRequestException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.VariableBinding;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;

/**
 * Polls every inventory device with two independent loops (amendments §1 and §2):
 * <ul>
 *   <li>a lightweight <b>probe</b> ({@code sysUpTime.0}) that never goes through the circuit breaker, so contact state
 *       is always driven by real attempts;</li>
 *   <li>a <b>full-status poll</b> guarded by the device's breaker and single-flight: a scheduled run while a full poll
 *       is queued or running is coalesced, and refresh requests (after a trap) collapse into exactly one
 *       {@code TRAP_REFRESH} poll once it ends.</li>
 * </ul>
 * All SNMP work runs on the bounded {@link PollDispatcher}. Results go to the board writer with the generation read
 * when the job started; this class never waits for the writer.
 *
 * <p>A loop's run starts when it fires. The next run fires at that start plus the device's poll interval ×
 * U[0.9, 1.1], or at once if that time has passed, so a slow run does not stretch the cycle and a loop never
 * overlaps itself; the wait is never longer than interval × 1.1, even if the wall clock steps back. The first run of
 * each loop fires after a random delay in [0, interval).
 *
 * <p>{@link BoardReady} starts the loops; as a {@link SmartLifecycle} the scheduler is stopped first on shutdown.
 */
public final class PollScheduler implements SmartLifecycle {

    /** The highest phase: stops before the SNMP session closes and before the board writer stops. */
    public static final int PHASE = SmartLifecycle.DEFAULT_PHASE;

    static final String COALESCED = "atmsup.poll.coalesced";
    static final String REJECTED = "atmsup.poll.rejected";

    private static final Logger log = LoggerFactory.getLogger(PollScheduler.class);
    private static final double JITTER_MIN = 0.9;
    private static final double JITTER_MAX = 1.1;

    /** Runs a task once after a delay; the loops use it to fire their next run. */
    @FunctionalInterface
    public interface Delayer {

        void schedule(Runnable r, Duration delay);

        /** Each delay sleeps on its own virtual thread: nothing to shut down, and a stopped loop ignores its wake-up. */
        static Delayer virtualThreads() {
            return (r, delay) -> Thread.ofVirtual().name("poll-timer").start(() -> {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    return;
                }
                r.run();
            });
        }
    }

    /** Single-flight state of one device's full polls; guarded by its own monitor. */
    private static final class FullPollSlot {

        /** A full poll of the device is queued or running. */
        boolean active;
        /** Set while a refresh is owed: one TRAP_REFRESH poll follows the active one and completes this future. */
        CompletableFuture<Void> refreshPending;
    }

    private final Inventory inventory;
    private final SnmpClient client;
    private final ObservationMapper mapper;
    private final BoardInputSink sink;
    private final GenerationSource generations;
    private final DeviceBreakers breakers;
    private final PollDispatcher dispatcher;
    private final InstantSource clock;
    private final RandomGenerator random;
    private final Delayer delayer;
    private final Counter coalesced;
    private final Counter rejected;
    private final Map<String, FullPollSlot> slots = new LinkedHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile boolean stopped;

    public PollScheduler(Inventory inventory, SnmpClient client, ObservationMapper mapper, BoardInputSink sink,
                         GenerationSource generations, DeviceBreakers breakers, PollDispatcher dispatcher,
                         InstantSource clock, RandomGenerator random, Delayer delayer, MeterRegistry meters) {
        this.inventory = inventory;
        this.client = client;
        this.mapper = mapper;
        this.sink = sink;
        this.generations = generations;
        this.breakers = breakers;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.random = random;
        this.delayer = delayer;
        this.coalesced = Counter.builder(COALESCED)
                .description("Scheduled full polls dropped because a full poll of the device was already active")
                .register(meters);
        this.rejected = Counter.builder(REJECTED)
                .description("Full polls not attempted because the device's circuit breaker did not permit them")
                .register(meters);
        inventory.devices().forEach(d -> slots.put(d.id(), new FullPollSlot()));
    }

    /** Starts a probe loop and a full-poll loop per device. Only schedules: it runs on the board writer thread. */
    @Override
    @EventListener(BoardReady.class)
    public void start() {
        if (stopped || !started.compareAndSet(false, true)) {
            return;
        }
        for (Device d : inventory.devices()) {
            delayer.schedule(() -> probeLoop(d), firstDelay(d));
            delayer.schedule(() -> fullPollLoop(d), firstDelay(d));
        }
    }

    /** Ends both loops and closes the dispatcher (queued jobs are dropped, running ones finish). Not restartable. */
    @Override
    public void stop() {
        stopped = true;
        dispatcher.close();
    }

    @Override
    public boolean isRunning() {
        return started.get() && !stopped;
    }

    /** Started by {@link BoardReady}, not by the context. */
    @Override
    public boolean isAutoStartup() {
        return false;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    /**
     * A trap, or a probe that restored contact, asked for a prompt full poll (amendment §1). After CONTACT_RESTORED
     * the device's breaker is usually still OPEN from the failed polls, but a probe has proved the device reachable,
     * so exactly one HALF_OPEN full poll verifies its health, and only that poll's success closes the breaker. A trap
     * leaves an OPEN breaker alone. Runs on the board writer thread and does not block: it only enqueues.
     */
    @EventListener
    public void onRepoll(RepollRequested r) {
        if (r.reason() == RepollRequested.Reason.CONTACT_RESTORED) {
            CircuitBreaker breaker = breakers.forDevice(r.equipmentId());
            if (breaker.getState() == CircuitBreaker.State.OPEN) {
                breaker.transitionToHalfOpenState();
            }
        }
        requestRefresh(r.equipmentId());
    }

    // Test seams: each runs through the same code as the loops. The futures complete when the work is done (never,
    // once stopped: the closed dispatcher drops the work).

    /** One run of the probe loop: the probe, then the next run is scheduled. */
    CompletableFuture<Void> probeNow(String equipmentId) {
        return probe(inventory.device(equipmentId));
    }

    /** A SCHEDULED_FULL full-poll request, as the full-poll loop makes it, without scheduling a next run. */
    CompletableFuture<Void> fullPollNow(String equipmentId) {
        return requestFullPoll(inventory.device(equipmentId), Priority.SCHEDULED_FULL);
    }

    /** One run of the full-poll loop: the request, then the next run is scheduled. */
    CompletableFuture<Void> scheduledFullPoll(String equipmentId) {
        return scheduledFullPoll(inventory.device(equipmentId));
    }

    /** A TRAP_REFRESH full-poll request; it completes when a full poll started after the request has finished. */
    CompletableFuture<Void> requestRefresh(String equipmentId) {
        return requestFullPoll(inventory.device(equipmentId), Priority.TRAP_REFRESH);
    }

    // --- loops ---------------------------------------------------------------------------------------------------

    private void probeLoop(Device d) {
        if (!stopped) {
            probe(d);
        }
    }

    private void fullPollLoop(Device d) {
        if (!stopped) {
            scheduledFullPoll(d);
        }
    }

    private CompletableFuture<Void> probe(Device d) {
        Instant start = clock.instant();
        CompletableFuture<Void> done = new CompletableFuture<>();
        dispatcher.submit(Priority.PROBE, () -> {
            try {
                runProbe(d);
            } finally {
                scheduleNext(d, start, () -> probeLoop(d));
                done.complete(null);
            }
        });
        return done;
    }

    private CompletableFuture<Void> scheduledFullPoll(Device d) {
        Instant start = clock.instant();
        return requestFullPoll(d, Priority.SCHEDULED_FULL)
                .whenComplete((ignored, failure) -> scheduleNext(d, start, () -> fullPollLoop(d)));
    }

    /**
     * Schedules the loop's next run at {@code start} + interval × U[0.9, 1.1]. The delay is clamped to
     * [0, interval × 1.1]: a wall clock that steps back during the job (a VM clock correction, say) must not stretch
     * the sleep, which would delay lost-contact detection by the size of the step.
     */
    private void scheduleNext(Device d, Instant start, Runnable run) {
        if (stopped) {
            return;
        }
        long intervalNanos = d.pollInterval().toNanos();
        double factor = random.nextDouble(JITTER_MIN, JITTER_MAX);
        Instant next = start.plus(Duration.ofNanos(Math.round(intervalNanos * factor)));
        Duration delay = Duration.between(clock.instant(), next);
        Duration max = Duration.ofNanos(Math.round(intervalNanos * JITTER_MAX));
        delayer.schedule(run, delay.isNegative() ? Duration.ZERO : delay.compareTo(max) > 0 ? max : delay);
    }

    private Duration firstDelay(Device d) {
        return Duration.ofNanos(random.nextLong(d.pollInterval().toNanos()));
    }

    // --- single-flight -------------------------------------------------------------------------------------------

    private CompletableFuture<Void> requestFullPoll(Device d, Priority priority) {
        FullPollSlot slot = slots.get(d.id());
        synchronized (slot) {
            if (slot.active) {
                if (priority == Priority.TRAP_REFRESH) {
                    if (slot.refreshPending == null) {
                        slot.refreshPending = new CompletableFuture<>();
                    }
                    return slot.refreshPending;
                }
                coalesced.increment();
                return CompletableFuture.completedFuture(null);
            }
            slot.active = true;
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        dispatcher.submit(priority, () -> fullPollJob(d, slot, done));
        return done;
    }

    /** One full poll; then the refresh owed (if any) is queued while the device stays active, else it is released. */
    private void fullPollJob(Device d, FullPollSlot slot, CompletableFuture<Void> done) {
        try {
            runFullPoll(d);
        } finally {
            CompletableFuture<Void> refresh;
            synchronized (slot) {
                refresh = slot.refreshPending;
                slot.refreshPending = null;
                slot.active = refresh != null;
            }
            if (refresh != null) {
                dispatcher.submit(Priority.TRAP_REFRESH, () -> fullPollJob(d, slot, refresh));
            }
            done.complete(null);
        }
    }

    // --- jobs ----------------------------------------------------------------------------------------------------

    private void runProbe(Device d) {
        long gen = generations.generation(d.id());
        BoardInput result;
        try {
            List<VariableBinding> vbs = client.get(d, List.of(PollPlan.PROBE));
            result = new ProbeSucceeded(mapper.mapProbe(d, vbs, clock.instant()), gen);
        } catch (SnmpRequestException e) {
            result = new ProbeFailed(d.id(), clock.instant(), e.getMessage(), gen);
        } catch (RuntimeException e) {
            log.warn("Probe of {} failed", d.id(), e);
            result = new ProbeFailed(d.id(), clock.instant(), "ERROR: " + e, gen);
        }
        sink.submit(result);
    }

    /**
     * A full poll through the breaker. A rejected call is not a contact attempt and submits nothing. Every permitted
     * call ends in exactly one of onSuccess, onError (SNMP failures) or releasePermission (anything else).
     */
    private void runFullPoll(Device d) {
        CircuitBreaker breaker = breakers.forDevice(d.id());
        if (!breaker.tryAcquirePermission()) {
            rejected.increment();
            return;
        }
        long gen = generations.generation(d.id());
        long started = System.nanoTime();
        Observation obs;
        try {
            obs = read(d);
        } catch (SnmpRequestException e) {
            breaker.onError(System.nanoTime() - started, NANOSECONDS, e);
            sink.submit(new PollFailed(d.id(), clock.instant(), e.getMessage(), gen));
            return;
        } catch (RuntimeException e) {
            breaker.releasePermission(); // neither a success nor a device failure: a HALF_OPEN trial stays available
            log.warn("Full poll of {} failed", d.id(), e);
            sink.submit(new PollFailed(d.id(), clock.instant(), "ERROR: " + e, gen));
            return;
        } catch (Error e) {
            breaker.releasePermission();
            throw e;
        }
        breaker.onSuccess(System.nanoTime() - started, NANOSECONDS);
        sink.submit(new PollSucceeded(obs, gen));
    }

    private Observation read(Device d) {
        PollPlan plan = PollPlan.forType(d.type());
        List<VariableBinding> scalars = client.get(d, plan.scalars());
        Map<OID, List<VariableBinding>> walks = new LinkedHashMap<>();
        for (OID subtree : plan.walks()) {
            walks.put(subtree, client.walk(d, subtree));
        }
        return mapper.map(d, scalars, walks, clock.instant());
    }
}
