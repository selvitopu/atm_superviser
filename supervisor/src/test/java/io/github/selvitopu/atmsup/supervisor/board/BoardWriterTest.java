package io.github.selvitopu.atmsup.supervisor.board;

import static io.github.selvitopu.atmsup.mib.EquipmentStatus.FAILED;
import static io.github.selvitopu.atmsup.mib.EquipmentStatus.OPERATIONAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.mib.EquipmentStatus;
import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmEngine;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmKey;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmSource;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmState;
import io.github.selvitopu.atmsup.supervisor.alarms.IdGenerator;
import io.github.selvitopu.atmsup.supervisor.board.BoardEvents.BoardCommitted;
import io.github.selvitopu.atmsup.supervisor.board.BoardEvents.BoardReady;
import io.github.selvitopu.atmsup.supervisor.board.BoardEvents.RepollRequested;
import io.github.selvitopu.atmsup.supervisor.board.BoardEvents.TelemetryUpdated;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.HeartbeatTick;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.PollSucceeded;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.ProbeFailed;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.TrapReceived;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.SupervisorEvent;
import io.github.selvitopu.atmsup.supervisor.CapturedLogs;
import io.github.selvitopu.atmsup.supervisor.snmp.Notification;
import io.github.selvitopu.atmsup.supervisor.snmp.Notification.AlarmRaisedNotification;
import io.github.selvitopu.atmsup.supervisor.snmp.Notification.StatusChangeNotification;
import io.github.selvitopu.atmsup.supervisor.snmp.Observation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class BoardWriterTest {

    private static final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration NO_HEARTBEAT = Duration.ofHours(1);

    private final Inventory inv = BoardFixtures.inventory(false);
    private final FakeStore store = new FakeStore();
    private final RecordingPublisher publisher = new RecordingPublisher();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AtomicReference<Instant> now = new AtomicReference<>(t0);
    private BoardWriter writer;

    private BoardWriter writer(Duration heartbeat) {
        var evaluator = new ServiceEvaluator(inv, List.of(new SurveillanceRule(), new FlightDataRule()));
        var decider = new BoardDecider(inv, new StatusDeriver(), evaluator, new AlarmEngine(IdGenerator.uuid()),
                Duration.ofSeconds(30));
        InstantSource clock = now::get;
        writer = new BoardWriter(inv, store, decider, clock, publisher, meters, heartbeat);
        return writer;
    }

    private BoardWriter started() {
        writer(NO_HEARTBEAT).start();
        return writer;
    }

    @AfterEach
    void stopWriter() {
        if (writer != null && writer.isRunning()) {
            writer.stop();
        }
    }

    private static Observation obs(String id, EquipmentStatus status) {
        return obs(id, t0, status, Map.of());
    }

    private static Observation obs(String id, Instant at, EquipmentStatus status, Map<String, Object> values) {
        return new Observation(id, at, status, Role.STANDALONE, false, values, Map.of());
    }

    private PollSucceeded pollSucceeded(String id, EquipmentStatus status) {
        return new PollSucceeded(obs(id, status), writer.generation(id));
    }

    private ProbeFailed probeFailed(String id) {
        return new ProbeFailed(id, t0, "TIMEOUT: no response", writer.generation(id));
    }

    private static Notification statusChange(String id) {
        return new StatusChangeNotification(id, t0);
    }

    private Health health(String id) {
        return writer.current().board().equipment().get(id).health();
    }

    @Test
    void appliesOnlyCommittedEventsAndPublishesAfterCommit() {
        started();
        publisher.clear();
        int before = store.events.size();

        BoardResult r = writer.submit(pollSucceeded("RAD-N", OPERATIONAL)).join();

        assertThat(r).isEqualTo(BoardResult.ok());
        assertThat(writer.current().board().lastEventId()).isPositive().isEqualTo(store.events.getLast().id());
        assertThat(health("RAD-N")).isEqualTo(Health.OPERATIONAL);
        assertThat(publisher.all).extracting(Object::getClass)
                .containsExactly(BoardCommitted.class, TelemetryUpdated.class);
        BoardCommitted committed = publisher.events(BoardCommitted.class).getFirst();
        assertThat(committed.events()).isEqualTo(store.events.subList(before, store.events.size()));
        assertThat(committed.board()).isSameAs(writer.current());
        TelemetryUpdated telemetry = publisher.events(TelemetryUpdated.class).getFirst();
        assertThat(telemetry.equipmentId()).isEqualTo("RAD-N");
        assertThat(telemetry.telemetry()).isEqualTo(writer.current().telemetry().get("RAD-N"));
        assertThat(telemetry.serverTime()).isEqualTo(t0);
        assertThat(store.commitThreads).containsOnly("board-writer");
        assertThat(publisher.threads).containsOnly("board-writer");
    }

    @Test
    void trapIncrementsGenerationSoInFlightResultIsDiscarded() {
        started();
        long g = writer.generation("RAD-N");

        writer.submit(new TrapReceived(statusChange("RAD-N"))).join();

        assertThat(writer.generation("RAD-N")).isEqualTo(g + 1);
        assertThat(publisher.events(RepollRequested.class)).containsExactly(new RepollRequested("RAD-N"));
        publisher.clear();
        BoardResult r = writer.submit(new PollSucceeded(obs("RAD-N", FAILED), g)).join();
        assertThat(r).isEqualTo(BoardResult.ok());
        assertThat(health("RAD-N")).isNotEqualTo(Health.FAILED);
        assertThat(publisher.all).isEmpty();
        assertThat(meters.counter("atmsup.board.stale.results", "kind", "poll").count()).isEqualTo(1.0);
        assertThat(meters.counter("atmsup.board.stale.results", "kind", "probe").count()).isZero();
    }

    @Test
    void commitFailureLeavesBoardUnchangedAndRetries() {
        started();
        publisher.clear();
        store.failNextCommit();

        BoardResult r;
        try (CapturedLogs logs = new CapturedLogs(BoardWriter.class)) {
            r = writer.submit(pollSucceeded("RAD-N", FAILED)).join();
            assertThat(logs.events()).extracting(ILoggingEvent::getLevel).containsExactly(Level.ERROR);
        }

        assertThat(r.committed()).isFalse();
        assertThat(r.error()).isEqualTo("database unavailable");
        assertThat(health("RAD-N")).isNotEqualTo(Health.FAILED);
        assertThat(publisher.events(BoardCommitted.class)).isEmpty();
        assertThat(publisher.all).extracting(Object::getClass).containsExactly(TelemetryUpdated.class);
        Telemetry published = publisher.events(TelemetryUpdated.class).getFirst().telemetry();
        assertThat(published.lastPollAt()).isNull(); // the poll was not persisted: no fresh poll time
        assertThat(published).isEqualTo(writer.current().telemetry().get("RAD-N"));
        writer.submit(pollSucceeded("RAD-N", FAILED)).join();
        assertThat(health("RAD-N")).isEqualTo(Health.FAILED);
    }

    @Test
    void commitFailureDoesNotLoseTheTelemetrySample() {
        started();
        publisher.clear();
        store.failNextCommit();

        try (CapturedLogs logs = new CapturedLogs(BoardWriter.class)) {
            assertThat(writer.submit(pollSucceeded("RAD-N", OPERATIONAL)).join().committed()).isFalse();
        }

        Telemetry kept = writer.current().telemetry().get("RAD-N");
        assertThat(kept.lastPollAt()).isNull();
        assertThat(kept.lastSampleAt()).isNull();
        assertThat(publisher.events(TelemetryUpdated.class)).extracting(TelemetryUpdated::telemetry)
                .containsExactly(kept);
        assertThat(writer.submit(pollSucceeded("RAD-N", OPERATIONAL)).join().committed()).isTrue();
        assertThat(store.events).extracting(e -> e.event().type()).contains(EventType.TELEMETRY_SAMPLED);
        assertThat(writer.current().telemetry().get("RAD-N").lastSampleAt()).isEqualTo(t0);
        assertThat(writer.current().telemetry().get("RAD-N").lastPollAt()).isEqualTo(t0);
    }

    @Test
    void commitFailureAfterASuccessfulPollKeepsTheLastPersistedPollAndValues() {
        started();
        Observation first = obs("RAD-N", t0, OPERATIONAL, Map.of("plotsPerScan", 300L));
        assertThat(writer.submit(new PollSucceeded(first, writer.generation("RAD-N"))).join().committed()).isTrue();
        Telemetry persisted = writer.current().telemetry().get("RAD-N");
        writer.submit(probeFailed("RAD-N")).join();
        assertThat(writer.current().telemetry().get("RAD-N").consecutiveFailures()).isEqualTo(1);
        now.set(t0.plusSeconds(5));
        publisher.clear();
        store.failNextCommit();

        Observation failed = obs("RAD-N", t0.plusSeconds(5), FAILED, Map.of("plotsPerScan", 0L));
        try (CapturedLogs logs = new CapturedLogs(BoardWriter.class)) {
            assertThat(writer.submit(new PollSucceeded(failed, writer.generation("RAD-N"))).join().committed())
                    .isFalse();
        }

        Telemetry kept = writer.current().telemetry().get("RAD-N");
        assertThat(kept.lastPollAt()).isEqualTo(t0);
        assertThat(kept.values()).isEqualTo(persisted.values()).containsEntry("plotsPerScan", 300L);
        assertThat(kept.tables()).isEqualTo(persisted.tables());
        assertThat(kept.lastSampleAt()).isEqualTo(persisted.lastSampleAt());
        // the attempt counters are merged: the device did answer
        assertThat(kept.lastProbeAt()).isEqualTo(t0.plusSeconds(5));
        assertThat(kept.consecutiveFailures()).isZero();
        assertThat(kept.fullPollFailures()).isZero();
        assertThat(health("RAD-N")).isEqualTo(Health.OPERATIONAL);
        assertThat(publisher.all).extracting(Object::getClass).containsExactly(TelemetryUpdated.class);
        assertThat(publisher.events(TelemetryUpdated.class).getFirst().telemetry()).isEqualTo(kept);
    }

    @Test
    void failedTrapCommitStillRequestsARepollAndKeepsTheGeneration() {
        started();
        long g = writer.generation("RAD-N");
        publisher.clear();
        store.failNextCommit();

        try (CapturedLogs logs = new CapturedLogs(BoardWriter.class)) {
            BoardResult r = writer.submit(new TrapReceived(new AlarmRaisedNotification("RAD-N", t0, 101, 0, "",
                    Severity.CRITICAL, "Radar failure"))).join();
            assertThat(r.committed()).isFalse();
        }

        assertThat(publisher.all).containsExactly(new RepollRequested("RAD-N"));
        assertThat(writer.generation("RAD-N")).isEqualTo(g);
        assertThat(writer.current().board().openAlarms()).isEmpty();
    }

    @Test
    void commitFailureKeepsGenerationButMergesTheCounters() {
        started();
        writer.submit(probeFailed("RAD-N")).join();
        writer.submit(probeFailed("RAD-N")).join();
        store.failNextCommit();

        try (CapturedLogs logs = new CapturedLogs(BoardWriter.class)) {
            assertThat(writer.submit(probeFailed("RAD-N")).join().committed()).isFalse();
        }

        assertThat(writer.generation("RAD-N")).isZero();
        assertThat(writer.current().telemetry().get("RAD-N").consecutiveFailures()).isEqualTo(3);
        assertThat(writer.current().board().equipment().get("RAD-N").unknownReason())
                .isEqualTo(UnknownReason.NOT_YET_POLLED);
        assertThat(writer.submit(probeFailed("RAD-N")).join().committed()).isTrue();
        assertThat(writer.current().board().equipment().get("RAD-N").unknownReason())
                .isEqualTo(UnknownReason.LOST_CONTACT);
        assertThat(writer.generation("RAD-N")).isEqualTo(1);
    }

    @Test
    void firstStartIsNotYetPolledWithAllGenerationsZero() {
        started();

        assertThat(store.events).extracting(e -> e.event().type()).containsExactly(EventType.SUPERVISOR_STARTED,
                EventType.SERVICE_STATE_CHANGED, EventType.SERVICE_STATE_CHANGED);
        assertThat(writer.current().board().equipment().values()).extracting(EquipmentState::unknownReason)
                .containsOnly(UnknownReason.NOT_YET_POLLED);
        assertThat(writer.current().generations()).hasSize(7).containsOnlyKeys(
                inv.devices().stream().map(Device::id).toList());
        assertThat(writer.current().generations().values()).containsOnly(0L);
        assertThat(publisher.all).extracting(Object::getClass).containsExactly(BoardCommitted.class, BoardReady.class);
    }

    @Test
    void restartLoadsOpenAlarmsAndMarksSupervisorRestarted() {
        store.preload(new SupervisorEvent(EventType.SUPERVISOR_HEARTBEAT, t0.minusSeconds(60), null));
        Alarm lost = new Alarm("a1", new AlarmKey("RAD-N", 901, 0), "", Severity.MAJOR, "Lost contact",
                AlarmSource.SUPERVISOR, AlarmState.ACTIVE_UNACK, false, t0.minusSeconds(120), null, null, null, null);
        store.openAlarms.add(lost);

        started();

        assertThat(writer.current().board().openAlarms()).containsExactly(Map.entry(lost.key(), lost));
        assertThat(writer.current().board().equipment().values())
                .extracting(EquipmentState::health, EquipmentState::unknownReason)
                .containsOnly(tuple(Health.UNKNOWN, UnknownReason.SUPERVISOR_RESTARTED));
        assertThat(((SupervisorEvent) store.events.get(1).event()).reason())
                .isEqualTo(UnknownReason.SUPERVISOR_RESTARTED);
        assertThat(writer.current().board().lastEventId()).isEqualTo(store.events.getLast().id());
    }

    @Test
    void heartbeatTicksAreCommitted() {
        writer(Duration.ofMillis(20)).start();

        await().atMost(Duration.ofSeconds(5)).until(() -> store.events.stream()
                .anyMatch(e -> e.event().type() == EventType.SUPERVISOR_HEARTBEAT));
    }

    @Test
    void stopCommitsShutdownAndRejectsLaterInput() {
        started();

        writer.stop();

        assertThat(writer.isRunning()).isFalse();
        assertThat(store.events.getLast().event().type()).isEqualTo(EventType.SUPERVISOR_STOPPING);
        int committed = store.events.size();
        BoardResult late = writer.submit(new HeartbeatTick()).join();
        assertThat(late.committed()).isFalse();
        assertThat(store.events).hasSize(committed);
    }

    @Test
    void failingListenerDoesNotStopTheWriter() {
        started();
        publisher.failOn(BoardCommitted.class);

        try (CapturedLogs logs = new CapturedLogs(BoardWriter.class)) {
            assertThat(writer.submit(pollSucceeded("RAD-N", OPERATIONAL)).join().committed()).isTrue();
            assertThat(writer.submit(pollSucceeded("RAD-E", OPERATIONAL)).join().committed()).isTrue();
            assertThat(logs.events()).extracting(ILoggingEvent::getFormattedMessage)
                    .containsOnly("A listener failed on BoardCommitted").hasSize(2);
            publisher.failOn(null);
        }

        assertThat(health("RAD-N")).isEqualTo(Health.OPERATIONAL);
        assertThat(health("RAD-E")).isEqualTo(Health.OPERATIONAL);
        assertThat(publisher.events(TelemetryUpdated.class)).hasSize(2);
    }

    /** In-memory store assigning ids 1, 2, 3, ...; it records the committing thread and can fail one commit. */
    static final class FakeStore implements BoardStore {

        final List<StoredEvent> events = new CopyOnWriteArrayList<>();
        final List<Alarm> openAlarms = new CopyOnWriteArrayList<>();
        final List<String> commitThreads = new CopyOnWriteArrayList<>();
        private volatile boolean failNext;
        private long nextId = 1;

        void failNextCommit() {
            failNext = true;
        }

        void preload(DomainEvent e) {
            events.add(new StoredEvent(nextId++, e));
        }

        @Override
        public synchronized List<StoredEvent> commit(List<DomainEvent> batch) {
            commitThreads.add(Thread.currentThread().getName());
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("database unavailable");
            }
            List<StoredEvent> out = new ArrayList<>();
            for (DomainEvent e : batch) {
                out.add(new StoredEvent(nextId++, e));
            }
            events.addAll(out);
            return out;
        }

        @Override
        public List<Alarm> loadOpenAlarms() {
            return List.copyOf(openAlarms);
        }

        @Override
        public boolean hasEvents() {
            return !events.isEmpty();
        }
    }

    /** Records every published event and the publishing thread; can throw for one event type. */
    static final class RecordingPublisher implements ApplicationEventPublisher {

        final List<Object> all = new CopyOnWriteArrayList<>();
        final List<String> threads = new CopyOnWriteArrayList<>();
        private volatile Class<?> failOn;

        void failOn(Class<?> type) {
            failOn = type;
        }

        void clear() {
            all.clear();
            threads.clear();
        }

        <T> List<T> events(Class<T> type) {
            return all.stream().filter(type::isInstance).map(type::cast).toList();
        }

        @Override
        public void publishEvent(Object event) {
            all.add(event);
            threads.add(Thread.currentThread().getName());
            if (failOn != null && failOn.isInstance(event)) {
                throw new IllegalStateException("listener failed");
            }
        }
    }
}
