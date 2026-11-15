package io.github.selvitopu.atmsup.supervisor.board;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.supervisor.board.BoardEvents.BoardCommitted;
import io.github.selvitopu.atmsup.supervisor.board.BoardEvents.BoardReady;
import io.github.selvitopu.atmsup.supervisor.board.BoardEvents.RepollRequested;
import io.github.selvitopu.atmsup.supervisor.board.BoardEvents.TelemetryUpdated;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.HeartbeatTick;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.ProbeFailed;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.ProbeSucceeded;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.Shutdown;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.Startup;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;

/**
 * The only writer of the board. One platform thread, {@code board-writer}, takes the inputs in submission order;
 * for each it decides, commits the events in one transaction, applies only what was committed, then publishes.
 *
 * <p>A failed commit leaves the board and the generations unchanged, so the next equivalent input retries. Of the new
 * telemetry only the attempt counters are merged ({@code lastProbeAt}, {@code consecutiveFailures},
 * {@code fullPollFailures}); {@code lastPollAt}, the values, the tables and {@code lastSampleAt} stay as they were, so
 * an unpersisted result never looks like a fresh poll and an unstored sample stays due. A {@link TelemetryUpdated} is
 * still published with that counter-only telemetry, so the stream matches {@code /board}; its poll time and values
 * are the old ones. A trap's {@link RepollRequested} is published even then, so a follow-up poll retries.
 *
 * <p>Readers get an immutable snapshot through {@link #current()}.
 */
public final class BoardWriter implements BoardInputSink, GenerationSource, SmartLifecycle {

    /** Starts before the web server and the SNMP session and stops after them: inputs always reach a running writer. */
    public static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;

    static final String THREAD_NAME = "board-writer";
    static final String STALE_RESULTS = "atmsup.board.stale.results";

    private static final Logger log = LoggerFactory.getLogger(BoardWriter.class);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);
    private static final String NOT_RUNNING = "The board writer is not running";

    private final Inventory inv;
    private final BoardStore store;
    private final BoardDecider decider;
    private final InstantSource clock;
    private final ApplicationEventPublisher publisher;
    private final Counter staleProbes;
    private final Counter stalePolls;
    private final Duration heartbeatInterval;

    private volatile LiveBoard live;
    private volatile ScheduledExecutorService executor;
    private volatile boolean running;
    /** Set on the writer thread once Shutdown is processed: SUPERVISOR_STOPPING stays the last event. */
    private volatile boolean shutDown;

    public BoardWriter(Inventory inv, BoardStore store, BoardDecider decider, InstantSource clock,
                       ApplicationEventPublisher publisher, MeterRegistry meters, Duration heartbeatInterval) {
        this.inv = inv;
        this.store = store;
        this.decider = decider;
        this.clock = clock;
        this.publisher = publisher;
        this.heartbeatInterval = heartbeatInterval;
        this.staleProbes = staleCounter(meters, "probe");
        this.stalePolls = staleCounter(meters, "poll");
        this.live = new LiveBoard(Board.initial(inv, List.of()), Map.of(), zeroGenerations(inv));
    }

    private static Counter staleCounter(MeterRegistry meters, String kind) {
        return Counter.builder(STALE_RESULTS).tag("kind", kind)
                .description("Probe or poll results discarded because the device generation moved on")
                .register(meters);
    }

    private static Map<String, Long> zeroGenerations(Inventory inv) {
        Map<String, Long> gens = new LinkedHashMap<>();
        inv.devices().stream().map(Device::id).forEach(id -> gens.put(id, 0L));
        return gens;
    }

    /** The latest board: an immutable snapshot, safe to read from any thread. */
    public LiveBoard current() {
        return live;
    }

    @Override
    public long generation(String equipmentId) {
        return live.generation(equipmentId);
    }

    @Override
    public CompletableFuture<BoardResult> submit(BoardInput in) {
        ScheduledExecutorService ex = executor;
        CompletableFuture<BoardResult> result = new CompletableFuture<>();
        if (ex == null) {
            return CompletableFuture.completedFuture(BoardResult.failed(NOT_RUNNING));
        }
        try {
            ex.execute(() -> result.complete(process(in)));
        } catch (RejectedExecutionException e) {
            result.complete(BoardResult.failed(NOT_RUNNING));
        }
        return result;
    }

    /**
     * Loads the open alarms, commits the start-up events, publishes {@link BoardReady} and schedules the heartbeat.
     */
    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        boolean firstStart = !store.hasEvents();
        live = new LiveBoard(Board.initial(inv, store.loadOpenAlarms()), Map.of(), zeroGenerations(inv));
        shutDown = false;
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name(THREAD_NAME).daemon().factory());
        executor = ex;
        submit(new Startup(firstStart)).join();
        CompletableFuture.runAsync(() -> publish(new BoardReady()), ex).join();
        long period = heartbeatInterval.toMillis();
        ex.scheduleAtFixedRate(() -> process(new HeartbeatTick()), period, period, TimeUnit.MILLISECONDS);
        running = true;
    }

    /** Commits SUPERVISOR_STOPPING after the inputs already queued, then stops the thread; later inputs fail. */
    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        ScheduledExecutorService ex = executor;
        CompletableFuture<BoardResult> stopping = submit(new Shutdown());
        executor = null;
        ex.shutdown(); // queued inputs still run; the periodic heartbeat is cancelled
        try {
            stopping.get(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!ex.awaitTermination(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                ex.shutdownNow();
            }
        } catch (TimeoutException e) {
            log.warn("The board writer did not stop within {}", STOP_TIMEOUT);
            ex.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ex.shutdownNow();
        } catch (ExecutionException e) {
            throw new IllegalStateException(e); // process() never throws
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    /** Runs on the writer thread only. Never throws. */
    private BoardResult process(BoardInput in) {
        if (shutDown) {
            return BoardResult.failed(NOT_RUNNING);
        }
        if (in instanceof Shutdown) {
            shutDown = true;
        }
        Instant now = clock.instant();
        LiveBoard before = live;
        Decision d;
        try {
            d = decider.decide(before, in, now);
        } catch (RuntimeException e) {
            log.error("Cannot process {}", in.getClass().getSimpleName(), e);
            return BoardResult.failed(e.getMessage());
        }
        if (d.stale()) {
            (in instanceof ProbeSucceeded || in instanceof ProbeFailed ? staleProbes : stalePolls).increment();
            return BoardResult.ok();
        }

        List<StoredEvent> stored;
        try {
            stored = d.events().isEmpty() ? List.of() : store.commit(d.events());
        } catch (RuntimeException e) {
            log.error("Cannot commit the events of {}; the board is unchanged", in.getClass().getSimpleName(), e);
            Map<String, Telemetry> counters = countersOnly(before.telemetry(), d.telemetry());
            live = new LiveBoard(before.board(), merged(before.telemetry(), counters), before.generations());
            publishTelemetry(counters, now);
            d.repoll().forEach(this::publish);
            return BoardResult.failed(e.getMessage());
        }

        Board board = before.board();
        for (StoredEvent e : stored) {
            board = BoardReducer.apply(board, e);
        }
        LiveBoard after = new LiveBoard(board, merged(before.telemetry(), d.telemetry()),
                bumped(before.generations(), d.invalidated()));
        live = after;
        if (!stored.isEmpty()) {
            publish(new BoardCommitted(stored, after));
        }
        publishTelemetry(d.telemetry(), now);
        d.repoll().forEach(this::publish);
        return d.result();
    }

    private static Map<String, Telemetry> merged(Map<String, Telemetry> current, Map<String, Telemetry> updates) {
        Map<String, Telemetry> m = new LinkedHashMap<>(current);
        m.putAll(updates);
        return m;
    }

    /**
     * The updates' attempt counters on the current telemetry: what the unpersisted result measured (its poll time,
     * values and tables) is dropped, and a sample that was not stored stays due for the next poll.
     */
    private static Map<String, Telemetry> countersOnly(Map<String, Telemetry> current,
                                                       Map<String, Telemetry> updates) {
        Map<String, Telemetry> m = new LinkedHashMap<>();
        updates.forEach((id, t) -> {
            Telemetry kept = current.getOrDefault(id, Telemetry.EMPTY);
            m.put(id, new Telemetry(kept.lastPollAt(), t.lastProbeAt(), t.consecutiveFailures(), t.fullPollFailures(),
                    kept.lastSampleAt(), kept.values(), kept.tables()));
        });
        return m;
    }

    private static Map<String, Long> bumped(Map<String, Long> current, Set<String> invalidated) {
        Map<String, Long> m = new LinkedHashMap<>(current);
        invalidated.forEach(id -> m.merge(id, 1L, Long::sum));
        return m;
    }

    private void publishTelemetry(Map<String, Telemetry> telemetry, Instant now) {
        telemetry.forEach((id, t) -> publish(new TelemetryUpdated(id, t, now)));
    }

    /** Listeners only enqueue; one that throws must not stop the writer. */
    private void publish(Object event) {
        try {
            publisher.publishEvent(event);
        } catch (RuntimeException e) {
            log.error("A listener failed on {}", event.getClass().getSimpleName(), e);
        }
    }
}
