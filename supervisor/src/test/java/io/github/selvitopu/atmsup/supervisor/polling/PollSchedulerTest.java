package io.github.selvitopu.atmsup.supervisor.polling;

import static io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED;
import static io.github.resilience4j.circuitbreaker.CircuitBreaker.State.HALF_OPEN;
import static io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_ID;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_MAINTENANCE_MODE;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_ROLE;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_STATUS;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.scalar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.mib.EquipmentStatus;
import io.github.selvitopu.atmsup.simulator.fixture.TestInventories;
import io.github.selvitopu.atmsup.supervisor.CapturedLogs;
import io.github.selvitopu.atmsup.supervisor.board.BoardEvents.BoardReady;
import io.github.selvitopu.atmsup.supervisor.board.BoardEvents.RepollRequested;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.PollFailed;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.PollSucceeded;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.ProbeFailed;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.ProbeSucceeded;
import io.github.selvitopu.atmsup.supervisor.board.BoardInputSink;
import io.github.selvitopu.atmsup.supervisor.board.BoardResult;
import io.github.selvitopu.atmsup.supervisor.board.GenerationSource;
import io.github.selvitopu.atmsup.supervisor.snmp.ObservationMapper;
import io.github.selvitopu.atmsup.supervisor.snmp.PollPlan;
import io.github.selvitopu.atmsup.supervisor.snmp.SnmpClient;
import io.github.selvitopu.atmsup.supervisor.snmp.SnmpRequestException;
import io.github.selvitopu.atmsup.supervisor.snmp.SnmpRequestException.Kind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.Null;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.VariableBinding;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class PollSchedulerTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final Duration SHORT = Duration.ofMillis(100);
    private static final Instant T0 = Instant.parse("2026-11-14T10:00:00Z");

    private final Inventory inventory = Inventory.from(TestInventories.v01(16101, 10162));
    private final MutableClock clock = new MutableClock(T0);
    private final ScriptedClient client = new ScriptedClient(clock);
    private final RecordingSink sink = new RecordingSink();
    private final Generations gens = new Generations();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final DeviceBreakers breakers = new DeviceBreakers(meters, DeviceBreakers.defaults());
    private final RecordingDelayer delayer = new RecordingDelayer();
    private final List<PollDispatcher> dispatchers = new ArrayList<>();
    private final PollScheduler scheduler = scheduler(8);

    private PollScheduler scheduler(int maxConcurrent) {
        PollDispatcher dispatcher = new PollDispatcher(maxConcurrent);
        dispatchers.add(dispatcher);
        return new PollScheduler(inventory, client, new ObservationMapper(), sink, gens, breakers, dispatcher, clock,
                new Random(42), delayer, meters);
    }

    @AfterEach
    void closeDispatchers() {
        client.releaseAll();
        dispatchers.forEach(PollDispatcher::close);
    }

    private static void done(CompletableFuture<Void> job) {
        assertThat(job).succeedsWithin(WAIT);
    }

    private static SnmpRequestException timeout() {
        return new SnmpRequestException(Kind.TIMEOUT, "no response from 127.0.0.1:16101");
    }

    // --- probes --------------------------------------------------------------------------------------------------

    @Test
    void probeSuccessSubmitsProbeSucceededWithStartGeneration() {
        gens.set("RAD-N", 4);
        client.onGetSetGeneration("RAD-N", gens, 5);

        done(scheduler.probeNow("RAD-N"));

        ProbeSucceeded p = sink.only(ProbeSucceeded.class);
        assertThat(p.generation()).isEqualTo(4);
        assertThat(p.probe().equipmentId()).isEqualTo("RAD-N");
        assertThat(p.probe().sysUpTimeCentis()).isEqualTo(4200);
        assertThat(p.probe().observedAt()).isEqualTo(T0);
        assertThat(client.calls()).containsExactly("RAD-N probe");
    }

    @Test
    void failedProbeSubmitsProbeFailedWithReasonTimeAndGeneration() {
        gens.set("RAD-N", 2);
        client.failWith("RAD-N", timeout());

        done(scheduler.probeNow("RAD-N"));

        assertThat(sink.only(ProbeFailed.class))
                .isEqualTo(new ProbeFailed("RAD-N", T0, "TIMEOUT: no response from 127.0.0.1:16101", 2));
    }

    @Test
    void unexpectedProbeErrorIsAFailedProbeAndAWarning() {
        client.throwError("RAD-N", new IllegalStateException("boom"));

        try (CapturedLogs logs = new CapturedLogs(PollScheduler.class)) {
            done(scheduler.probeNow("RAD-N"));

            assertThat(sink.only(ProbeFailed.class).reason()).isEqualTo("ERROR: java.lang.IllegalStateException: boom");
            assertThat(logs.events()).singleElement().satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
        }
    }

    @Test
    void probesContinueWhileBreakerIsOpen() {
        CircuitBreaker b = breakers.forDevice("RAD-N");
        b.transitionToOpenState();
        client.failWith("RAD-N", timeout());

        done(scheduler.probeNow("RAD-N"));

        assertThat(sink.only(ProbeFailed.class).reason()).startsWith("TIMEOUT"); // a real attempt, counted
        client.succeed("RAD-N");
        sink.clear();
        done(scheduler.probeNow("RAD-N"));
        assertThat(sink.only(ProbeSucceeded.class)).isNotNull();
        assertThat(client.calls()).containsExactly("RAD-N probe", "RAD-N probe");
        // probes never ask the breaker, and a probe success does not close it
        assertThat(b.getState()).isEqualTo(OPEN);
        assertThat(b.getMetrics().getNumberOfNotPermittedCalls()).isZero();
        assertThat(b.getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    // --- full polls and the breaker ------------------------------------------------------------------------------

    @Test
    void fullPollSubmitsTheObservationWithStartGeneration() {
        gens.set("RAD-N", 7);
        client.onGetSetGeneration("RAD-N", gens, 8);

        done(scheduler.fullPollNow("RAD-N"));

        PollSucceeded p = sink.only(PollSucceeded.class);
        assertThat(p.generation()).isEqualTo(7);
        assertThat(p.obs().equipmentId()).isEqualTo("RAD-N");
        assertThat(p.obs().status()).isEqualTo(EquipmentStatus.OPERATIONAL);
        assertThat(p.obs().observedAt()).isEqualTo(T0);
    }

    @Test
    void fullPollWalksTheTablesOfThePlan() {
        done(scheduler.fullPollNow("SDPS-A"));

        assertThat(sink.only(PollSucceeded.class).obs().tables()).containsKey("sensors");
        assertThat(client.calls()).containsExactly("SDPS-A full", "SDPS-A walk " + PollPlan.forType(
                inventory.device("SDPS-A").type()).walks().getFirst());
    }

    @Test
    void breakerOpensAfterFailuresAndRejectedCallsSubmitNothing() {
        client.failWith("RAD-N", timeout());
        for (int i = 0; i < 3; i++) {
            done(scheduler.fullPollNow("RAD-N"));
        }
        assertThat(breakers.forDevice("RAD-N").getState()).isEqualTo(OPEN);
        assertThat(sink.inputs()).hasSize(3).allSatisfy(in -> assertThat(in).isInstanceOf(PollFailed.class));

        sink.clear();
        done(scheduler.fullPollNow("RAD-N"));

        assertThat(sink.inputs()).isEmpty();
        assertThat(client.fullPolls("RAD-N")).isEqualTo(3); // a rejected call is not a contact attempt
        assertThat(meters.counter("atmsup.poll.rejected").count()).isEqualTo(1.0);
    }

    @Test
    void halfOpenAllowsOneFullPollAndClosesOnSuccess() {
        CircuitBreaker b = breakers.forDevice("RAD-N");
        b.transitionToOpenState();
        b.transitionToHalfOpenState();
        client.succeed("RAD-N");

        done(scheduler.fullPollNow("RAD-N"));

        assertThat(b.getState()).isEqualTo(CLOSED);
        assertThat(sink.only(PollSucceeded.class)).isNotNull();
    }

    @Test
    void failedHalfOpenPollReopensTheBreaker() {
        CircuitBreaker b = breakers.forDevice("RAD-N");
        b.transitionToOpenState();
        b.transitionToHalfOpenState();
        client.failWith("RAD-N", timeout());

        done(scheduler.fullPollNow("RAD-N"));

        assertThat(b.getState()).isEqualTo(OPEN);
        assertThat(sink.only(PollFailed.class).reason()).startsWith("TIMEOUT");
    }

    @Test
    void unexpectedFullPollErrorReleasesTheHalfOpenPermission() {
        CircuitBreaker b = breakers.forDevice("RAD-N");
        b.transitionToOpenState();
        b.transitionToHalfOpenState();
        client.throwError("RAD-N", new IllegalStateException("boom"));

        try (CapturedLogs logs = new CapturedLogs(PollScheduler.class)) {
            done(scheduler.fullPollNow("RAD-N"));

            assertThat(sink.only(PollFailed.class).reason()).isEqualTo("ERROR: java.lang.IllegalStateException: boom");
            assertThat(logs.events()).singleElement().satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
        }
        assertThat(b.getState()).isEqualTo(HALF_OPEN); // neither a success nor a recorded failure

        client.succeed("RAD-N");
        sink.clear();
        done(scheduler.fullPollNow("RAD-N")); // the permission was given back, so this trial call is allowed

        assertThat(sink.only(PollSucceeded.class)).isNotNull();
        assertThat(b.getState()).isEqualTo(CLOSED);
    }

    @Test
    void timeoutThenRecovery() {
        gens.set("RAD-N", 3);
        client.failWith("RAD-N", timeout());
        done(scheduler.fullPollNow("RAD-N"));
        assertThat(sink.only(PollFailed.class))
                .isEqualTo(new PollFailed("RAD-N", T0, "TIMEOUT: no response from 127.0.0.1:16101", 3));

        client.succeed("RAD-N");
        sink.clear();
        done(scheduler.fullPollNow("RAD-N"));

        assertThat(sink.only(PollSucceeded.class).generation()).isEqualTo(3);
        assertThat(breakers.forDevice("RAD-N").getState()).isEqualTo(CLOSED);
    }

    // --- single-flight and refresh -------------------------------------------------------------------------------

    @Test
    void scheduledPollDuringActivePollIsCoalesced() {
        client.blockUntilReleased("RAD-N");
        CompletableFuture<Void> active = scheduler.fullPollNow("RAD-N");

        done(scheduler.scheduledFullPoll("RAD-N")); // dropped at once

        client.release("RAD-N");
        done(active);
        assertThat(client.fullPolls("RAD-N")).isEqualTo(1);
        assertThat(sink.only(PollSucceeded.class)).isNotNull();
        assertThat(meters.counter("atmsup.poll.coalesced").count()).isEqualTo(1.0);
    }

    @Test
    void trapsDuringActivePollCoalesceIntoExactlyOneRefresh() {
        client.blockUntilReleased("RAD-N");
        CompletableFuture<Void> active = scheduler.fullPollNow("RAD-N");
        List<CompletableFuture<Void>> refreshes = List.of(scheduler.requestRefresh("RAD-N"),
                scheduler.requestRefresh("RAD-N"), scheduler.requestRefresh("RAD-N"));
        assertThat(client.fullPolls("RAD-N")).isLessThanOrEqualTo(1);

        client.release("RAD-N");

        done(active);
        refreshes.forEach(PollSchedulerTest::done); // all three are answered by the one follow-up poll
        await().during(SHORT).atMost(WAIT).until(() -> client.fullPolls("RAD-N") == 2);
        assertThat(sink.inputs()).hasSize(2).allSatisfy(in -> assertThat(in).isInstanceOf(PollSucceeded.class));
    }

    @Test
    void refreshWithoutActivePollRunsAtOnce() {
        done(scheduler.requestRefresh("RAD-N"));

        assertThat(client.fullPolls("RAD-N")).isEqualTo(1);
        assertThat(sink.only(PollSucceeded.class)).isNotNull();
    }

    @Test
    void followUpRefreshRunsBeforeQueuedProbesAndScheduledPolls() {
        PollScheduler oneWorker = scheduler(1);
        client.blockUntilReleased("RAD-N");
        CompletableFuture<Void> active = oneWorker.fullPollNow("RAD-N");
        client.awaitBlocked("RAD-N");
        CompletableFuture<Void> queuedPoll = oneWorker.fullPollNow("RAD-E");
        CompletableFuture<Void> queuedProbe = oneWorker.probeNow("RAD-E");
        CompletableFuture<Void> refresh = oneWorker.requestRefresh("RAD-N");

        client.release("RAD-N");

        List.of(active, refresh, queuedProbe, queuedPoll).forEach(PollSchedulerTest::done);
        assertThat(client.calls()).containsExactly("RAD-N full", "RAD-N full", "RAD-E probe", "RAD-E full");
    }

    @Test
    void repollEventRequestsARefresh() {
        scheduler.onRepoll(new RepollRequested("RAD-N"));

        await().atMost(WAIT).until(() -> sink.inputs().size() == 1);
        assertThat(sink.only(PollSucceeded.class).obs().equipmentId()).isEqualTo("RAD-N");
    }

    // --- loop timing ---------------------------------------------------------------------------------------------

    @Test
    void nextDelayIsJitteredWithinTenPercent() {
        for (int i = 0; i < 200; i++) {
            done(scheduler.probeNow("RAD-N")); // the test clock does not advance during the job
        }

        assertThat(delayer.delays()).hasSize(200)
                .allSatisfy(d -> assertThat(d.toMillis()).isBetween(4500L, 5500L));
        assertThat(new HashSet<>(delayer.delays())).hasSizeGreaterThan(100);
        assertThat(delayer.delays().stream().mapToLong(Duration::toMillis).min().orElseThrow()).isLessThan(4600L);
        assertThat(delayer.delays().stream().mapToLong(Duration::toMillis).max().orElseThrow()).isGreaterThan(5400L);
    }

    @Test
    void slowPollDoesNotStretchTheCycle() {
        client.takeDuringFullPoll("RAD-N", Duration.ofSeconds(3));

        done(scheduler.scheduledFullPoll("RAD-N"));

        assertThat(delayer.delays()).singleElement()
                .satisfies(d -> assertThat(d.toMillis()).isBetween(1500L, 2500L));
    }

    @Test
    void overdueRunStartsImmediately() {
        client.takeDuringFullPoll("RAD-N", Duration.ofSeconds(6));

        done(scheduler.scheduledFullPoll("RAD-N"));

        assertThat(delayer.delays()).containsExactly(Duration.ZERO);
    }

    @Test
    void aBackwardClockStepDuringAJobNeverDelaysTheNextRunBeyondTheJitterBound() {
        client.takeDuringFullPoll("RAD-N", Duration.ofSeconds(-30)); // the wall clock steps back mid-job

        done(scheduler.scheduledFullPoll("RAD-N"));

        assertThat(delayer.delays()).singleElement()
                .satisfies(d -> assertThat(d).isBetween(Duration.ZERO, Duration.ofMillis(5500)));
    }

    @Test
    void aBackwardClockStepDuringAProbeNeverDelaysTheNextProbeBeyondTheJitterBound() {
        client.duringGet("RAD-N", () -> clock.advance(Duration.ofSeconds(-30)));

        done(scheduler.probeNow("RAD-N"));

        assertThat(delayer.delays()).singleElement()
                .satisfies(d -> assertThat(d).isBetween(Duration.ZERO, Duration.ofMillis(5500)));
    }

    @Test
    void coalescedScheduledRunStillSchedulesTheNextOne() {
        client.blockUntilReleased("RAD-N");
        scheduler.fullPollNow("RAD-N");

        done(scheduler.scheduledFullPoll("RAD-N"));

        assertThat(delayer.delays()).singleElement()
                .satisfies(d -> assertThat(d.toMillis()).isBetween(4500L, 5500L));
    }

    @Test
    void startRunsTwoIndependentLoopsPerDeviceWithRandomFirstDelays() {
        scheduler.start();

        assertThat(scheduler.isRunning()).isTrue();
        assertThat(delayer.delays()).hasSize(2 * inventory.devices().size())
                .allSatisfy(d -> assertThat(d.toMillis()).isBetween(0L, 4999L));
        assertThat(new HashSet<>(delayer.delays())).hasSizeGreaterThan(1);

        delayer.runAll(); // every loop fires once and schedules its next run

        await().atMost(WAIT).until(() -> delayer.delays().size() == 2 * inventory.devices().size());
        for (Device d : inventory.devices()) {
            assertThat(client.calls()).contains(d.id() + " probe", d.id() + " full");
        }
        assertThat(sink.inputs()).hasSize(2 * inventory.devices().size());
        assertThat(delayer.delays()).allSatisfy(d -> assertThat(d.toMillis()).isBetween(4500L, 5500L));
    }

    @Test
    void startIsIdempotent() {
        scheduler.start();
        scheduler.start();

        assertThat(delayer.delays()).hasSize(2 * inventory.devices().size());
    }

    @Test
    void stopEndsBothLoopsAndClosesTheDispatcher() {
        scheduler.start();

        scheduler.stop();
        delayer.runAll();
        scheduler.probeNow("RAD-N");

        assertThat(scheduler.isRunning()).isFalse();
        await().during(SHORT).atMost(WAIT).until(() -> client.calls().isEmpty());
        assertThat(delayer.delays()).isEmpty();
    }

    @Test
    void runsAsALifecycleBeanStartedByBoardReady() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(PollScheduler.class, () -> scheduler);
            ctx.refresh();
            assertThat(scheduler.isRunning()).isFalse(); // not auto-started

            ctx.publishEvent(new BoardReady());
            assertThat(scheduler.isRunning()).isTrue();
            assertThat(delayer.delays()).hasSize(2 * inventory.devices().size());

            ctx.publishEvent(new RepollRequested("RAD-N"));
            await().atMost(WAIT).until(() -> client.fullPolls("RAD-N") == 1);
        }

        assertThat(scheduler.isRunning()).isFalse();
    }

    @Test
    void virtualThreadDelayerRunsTheTaskAfterTheDelay() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        long start = System.nanoTime();

        PollScheduler.Delayer.virtualThreads().schedule(ran::countDown, Duration.ofMillis(20));

        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(Duration.ofMillis(20).toNanos());
    }

    // --- fakes ---------------------------------------------------------------------------------------------------

    /** A settable clock. */
    static final class MutableClock implements InstantSource {

        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            now = new AtomicReference<>(start);
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        void advance(Duration d) {
            now.updateAndGet(t -> t.plus(d));
        }
    }

    /** Settable per-device generations. */
    static final class Generations implements GenerationSource {

        private final Map<String, Long> gens = new ConcurrentHashMap<>();

        void set(String id, long gen) {
            gens.put(id, gen);
        }

        @Override
        public long generation(String equipmentId) {
            return gens.getOrDefault(equipmentId, 0L);
        }
    }

    /** Records the inputs; the writer "processes" each one at once. */
    static final class RecordingSink implements BoardInputSink {

        private final List<BoardInput> inputs = new CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<BoardResult> submit(BoardInput in) {
            inputs.add(in);
            return CompletableFuture.completedFuture(BoardResult.ok());
        }

        List<BoardInput> inputs() {
            return List.copyOf(inputs);
        }

        <T extends BoardInput> T only(Class<T> type) {
            assertThat(inputs).singleElement().isInstanceOf(type);
            return type.cast(inputs.getFirst());
        }

        void clear() {
            inputs.clear();
        }
    }

    /** Records each delay instead of waiting; {@link #runAll()} fires the recorded tasks once. */
    static final class RecordingDelayer implements PollScheduler.Delayer {

        private record Scheduled(Runnable task, Duration delay) {
        }

        private final List<Scheduled> scheduled = new CopyOnWriteArrayList<>();

        @Override
        public void schedule(Runnable r, Duration delay) {
            scheduled.add(new Scheduled(r, delay));
        }

        List<Duration> delays() {
            return scheduled.stream().map(Scheduled::delay).toList();
        }

        void runAll() {
            List<Scheduled> due = List.copyOf(scheduled);
            scheduled.removeAll(due);
            due.forEach(s -> s.task().run());
        }
    }

    /**
     * A scriptable agent per device. By default every device answers: a probe with sysUpTime 4200 and a full poll with
     * an operational, standalone, not-in-maintenance device (the other objects are noSuchObject); walks are empty.
     */
    static final class ScriptedClient implements SnmpClient {

        private final MutableClock clock;
        private final Map<String, Script> scripts = new ConcurrentHashMap<>();
        private final List<String> calls = new CopyOnWriteArrayList<>();

        ScriptedClient(MutableClock clock) {
            this.clock = clock;
        }

        private static final class Script {
            volatile RuntimeException failure;
            volatile Runnable duringGet;
            volatile Duration fullPollTakes = Duration.ZERO;
            volatile CountDownLatch release;
            final CountDownLatch blocked = new CountDownLatch(1);
            final AtomicInteger fullPolls = new AtomicInteger();
        }

        private Script script(String id) {
            return scripts.computeIfAbsent(id, k -> new Script());
        }

        void succeed(String id) {
            script(id).failure = null;
        }

        void failWith(String id, SnmpRequestException e) {
            script(id).failure = e;
        }

        void throwError(String id, RuntimeException e) {
            script(id).failure = e;
        }

        void onGetSetGeneration(String id, Generations gens, long gen) {
            duringGet(id, () -> gens.set(id, gen));
        }

        void duringGet(String id, Runnable r) {
            script(id).duringGet = r;
        }

        void takeDuringFullPoll(String id, Duration d) {
            script(id).fullPollTakes = d;
        }

        void blockUntilReleased(String id) {
            script(id).release = new CountDownLatch(1);
        }

        void awaitBlocked(String id) {
            try {
                assertThat(script(id).blocked.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }

        void release(String id) {
            CountDownLatch latch = script(id).release;
            script(id).release = null;
            if (latch != null) {
                latch.countDown();
            }
        }

        void releaseAll() {
            scripts.keySet().forEach(this::release);
        }

        int fullPolls(String id) {
            return script(id).fullPolls.get();
        }

        List<String> calls() {
            return List.copyOf(calls);
        }

        @Override
        public List<VariableBinding> get(Device d, List<OID> oids) {
            Script s = script(d.id());
            boolean probe = oids.equals(List.of(PollPlan.PROBE));
            calls.add(d.id() + (probe ? " probe" : " full"));
            Runnable during = s.duringGet;
            if (during != null) {
                during.run();
            }
            if (!probe) {
                s.fullPolls.incrementAndGet();
                CountDownLatch release = s.release;
                if (release != null) {
                    s.blocked.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                clock.advance(s.fullPollTakes);
            }
            RuntimeException failure = s.failure;
            if (failure != null) {
                throw failure;
            }
            if (probe) {
                return List.of(new VariableBinding(PollPlan.PROBE, new TimeTicks(4200)));
            }
            List<VariableBinding> answer = new ArrayList<>();
            for (OID oid : oids) {
                answer.add(new VariableBinding(oid, answer(d, oid)));
            }
            return answer;
        }

        private static org.snmp4j.smi.Variable answer(Device d, OID oid) {
            if (oid.equals(scalar(EQ_ID))) {
                return new OctetString(d.id());
            }
            if (oid.equals(scalar(EQ_STATUS)) || oid.equals(scalar(EQ_ROLE))) {
                return new Integer32(1); // operational, standalone
            }
            if (oid.equals(scalar(EQ_MAINTENANCE_MODE))) {
                return new Integer32(2); // TruthValue false
            }
            return Null.noSuchObject;
        }

        @Override
        public List<VariableBinding> walk(Device d, OID subtree) {
            calls.add(d.id() + " walk " + subtree);
            RuntimeException failure = script(d.id()).failure;
            if (failure != null) {
                throw failure;
            }
            return List.of();
        }
    }
}
