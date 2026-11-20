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
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmCorrelator;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmEngine;
import io.github.selvitopu.atmsup.supervisor.alarms.CorrelationTopology;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmKey;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmSource;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmState;
import io.github.selvitopu.atmsup.supervisor.alarms.IdGenerator;
import io.github.selvitopu.atmsup.supervisor.alarms.Via;
import io.github.selvitopu.atmsup.supervisor.board.BoardEvents.BoardCommitted;
import io.github.selvitopu.atmsup.supervisor.board.BoardEvents.BoardReady;
import io.github.selvitopu.atmsup.supervisor.board.BoardEvents.RepollRequested;
import io.github.selvitopu.atmsup.supervisor.board.BoardEvents.RepollRequested.Reason;
import io.github.selvitopu.atmsup.supervisor.board.BoardEvents.TelemetryUpdated;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.HeartbeatTick;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.PollFailed;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.PollSucceeded;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.ProbeFailed;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.ProbeSucceeded;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.TrapReceived;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.AlarmEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.EquipmentEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.SupervisorEvent;
import io.github.selvitopu.atmsup.supervisor.CapturedLogs;
import io.github.selvitopu.atmsup.supervisor.snmp.Notification;
import io.github.selvitopu.atmsup.supervisor.snmp.Notification.AlarmRaisedNotification;
import io.github.selvitopu.atmsup.supervisor.snmp.Notification.StatusChangeNotification;
import io.github.selvitopu.atmsup.supervisor.snmp.Observation;
import io.github.selvitopu.atmsup.supervisor.snmp.ProbeObservation;
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
    private final FakeStore store = new FakeStore(inv);
    private final RecordingPublisher publisher = new RecordingPublisher();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AtomicReference<Instant> now = new AtomicReference<>(t0);
    private final FakeSnapshots snapshots = new FakeSnapshots();
    private BoardWriter writer;

    private BoardWriter writer(Duration heartbeat) {
        return writer(heartbeat, AlarmFloodPolicy.DEFAULT);
    }

    private BoardWriter writer(Duration heartbeat, AlarmFloodPolicy floodPolicy) {
        var evaluator = new ServiceEvaluator(inv, List.of(new SurveillanceRule(), new FlightDataRule()));
        var decider = new BoardDecider(inv, new StatusDeriver(), evaluator, new AlarmEngine(IdGenerator.uuid()),
                new AlarmCorrelator(CorrelationTopology.from(inv)), Duration.ofSeconds(30), floodPolicy);
        InstantSource clock = now::get;
        writer = new BoardWriter(inv, store, decider, clock, publisher, meters, heartbeat,
                snapshots, Duration.ofMinutes(5));
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
        return new Observation(id, at, status, Role.STANDALONE, false, values, Map.of(), List.of());
    }

    private PollSucceeded pollSucceeded(String id, EquipmentStatus status) {
        return new PollSucceeded(obs(id, status), writer.generation(id));
    }

    private ProbeFailed probeFailed(String id) {
        return new ProbeFailed(id, t0, "TIMEOUT: no response", writer.contactGeneration(id));
    }

    private static Notification statusChange(String id) {
        return new StatusChangeNotification(id, t0);
    }

    private Health health(String id) {
        return writer.current().board().equipment().get(id).health();
    }

    private double stale(String kind) {
        return meters.counter("atmsup.board.stale.results", "kind", kind).count();
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
        assertThat(publisher.events(RepollRequested.class)).containsExactly(new RepollRequested("RAD-N", Reason.TRAP));
        publisher.clear();
        BoardResult r = writer.submit(new PollSucceeded(obs("RAD-N", FAILED), g)).join();
        assertThat(r).isEqualTo(BoardResult.ok());
        assertThat(health("RAD-N")).isNotEqualTo(Health.FAILED);
        assertThat(publisher.all).isEmpty();
        assertThat(meters.counter("atmsup.board.stale.results", "kind", "poll").count()).isEqualTo(1.0);
        assertThat(meters.counter("atmsup.board.stale.results", "kind", "probe").count()).isZero();
    }

    private TrapReceived raise(int code) {
        return new TrapReceived(new AlarmRaisedNotification("RAD-N", now.get(), code, 0, "", Severity.MINOR,
                "Fault " + code));
    }

    @Test
    void theWindowIsKeptAcrossDecisions() {
        writer(NO_HEARTBEAT, new AlarmFloodPolicy(Duration.ofMinutes(10), 3, 2)).start();
        assertThat(writer.current().recentRaises()).isEmpty();

        writer.submit(raise(201)).join();
        now.set(t0.plusSeconds(1));
        writer.submit(raise(202)).join();
        now.set(t0.plusSeconds(2));
        writer.submit(new TrapReceived(statusChange("RAD-N"))).join(); // a decision without a raise keeps it
        assertThat(writer.current().recentRaises()).containsExactly(t0, t0.plusSeconds(1));
        assertThat(writer.current().board().flood().active()).isFalse();

        writer.submit(raise(203)).join();

        assertThat(writer.current().recentRaises()).containsExactly(t0, t0.plusSeconds(1), t0.plusSeconds(2));
        assertThat(writer.current().board().flood()).isEqualTo(new AlarmFlood(true, t0.plusSeconds(2)));
        assertThat(store.events).extracting(e -> e.event().type()).contains(EventType.ALARM_FLOOD_STARTED);
    }

    @Test
    void aFailedCommitLeavesTheFloodWindowAsItWas() {
        writer(NO_HEARTBEAT, new AlarmFloodPolicy(Duration.ofMinutes(10), 3, 2)).start();
        writer.submit(raise(201)).join();
        now.set(t0.plusSeconds(1));
        writer.submit(raise(202)).join();
        now.set(t0.plusSeconds(2));
        store.failNextCommit();

        try (CapturedLogs logs = new CapturedLogs(BoardWriter.class)) {
            assertThat(writer.submit(raise(203)).join().committed()).isFalse();
        }

        assertThat(writer.current().board().flood().active()).isFalse();
        assertThat(writer.current().recentRaises()).containsExactly(t0, t0.plusSeconds(1));
        now.set(t0.plusSeconds(3));

        assertThat(writer.submit(raise(203)).join().committed()).isTrue();

        assertThat(writer.current().board().flood()).isEqualTo(new AlarmFlood(true, t0.plusSeconds(3)));
        assertThat(writer.current().recentRaises()).containsExactly(t0, t0.plusSeconds(1), t0.plusSeconds(3));
    }

    @Test
    void trapFloodCannotMaskLostContact() {
        started();
        assertThat(writer.submit(pollSucceeded("RAD-N", OPERATIONAL)).join().committed()).isTrue();
        assertThat(health("RAD-N")).isEqualTo(Health.OPERATIONAL);

        for (int i = 0; i < 3; i++) {
            long contact = writer.contactGeneration("RAD-N");
            writer.submit(new TrapReceived(statusChange("RAD-N"))).join();
            writer.submit(new ProbeFailed("RAD-N", t0, "TIMEOUT: no response", contact)).join();
        }

        EquipmentState s = writer.current().board().equipment().get("RAD-N");
        assertThat(s.health()).isEqualTo(Health.UNKNOWN);
        assertThat(s.unknownReason()).isEqualTo(UnknownReason.LOST_CONTACT);
        assertThat(writer.current().board().openAlarms().get(new AlarmKey("RAD-N", 901, 0)).state())
                .isEqualTo(AlarmState.ACTIVE_UNACK);
        assertThat(stale("probe")).isZero();
    }

    @Test
    void aContactChangeStillDiscardsAnOlderProbe() {
        started();
        long preLoss = writer.contactGeneration("RAD-N");
        for (int i = 0; i < 3; i++) {
            writer.submit(probeFailed("RAD-N")).join();
        }
        assertThat(writer.current().board().equipment().get("RAD-N").unknownReason())
                .isEqualTo(UnknownReason.LOST_CONTACT);
        assertThat(writer.contactGeneration("RAD-N")).isEqualTo(preLoss + 1);
        LiveBoard before = writer.current();
        publisher.clear();

        BoardResult r = writer.submit(new ProbeSucceeded(new ProbeObservation("RAD-N", t0.plusSeconds(5), 1000),
                preLoss)).join();

        assertThat(r).isEqualTo(BoardResult.ok());
        assertThat(writer.current()).isSameAs(before);
        assertThat(writer.current().board().equipment().get("RAD-N").unknownReason())
                .isEqualTo(UnknownReason.LOST_CONTACT);
        assertThat(writer.current().board().openAlarms().get(new AlarmKey("RAD-N", 901, 0)).state())
                .isEqualTo(AlarmState.ACTIVE_UNACK);
        assertThat(writer.current().telemetry().get("RAD-N").consecutiveFailures()).isEqualTo(3);
        assertThat(publisher.all).isEmpty();
        assertThat(stale("probe")).isEqualTo(1.0);
    }

    @Test
    void aTrapStillDiscardsAnOlderFullPoll() {
        started();
        assertThat(writer.submit(pollSucceeded("RAD-N", OPERATIONAL)).join().committed()).isTrue();
        long status = writer.generation("RAD-N");
        long contact = writer.contactGeneration("RAD-N");

        writer.submit(new TrapReceived(statusChange("RAD-N"))).join();

        assertThat(writer.generation("RAD-N")).isEqualTo(status + 1);
        assertThat(writer.contactGeneration("RAD-N")).isEqualTo(contact);
        LiveBoard before = writer.current();
        publisher.clear();
        assertThat(writer.submit(new PollSucceeded(obs("RAD-N", FAILED), status)).join()).isEqualTo(BoardResult.ok());
        assertThat(writer.submit(new PollFailed("RAD-N", t0, "TIMEOUT: no response", status)).join())
                .isEqualTo(BoardResult.ok());
        assertThat(writer.current()).isSameAs(before);
        assertThat(health("RAD-N")).isEqualTo(Health.OPERATIONAL);
        assertThat(writer.current().telemetry().get("RAD-N").fullPollFailures()).isZero();
        assertThat(publisher.all).isEmpty();
        assertThat(stale("poll")).isEqualTo(2.0);
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
    void failedCommitStillBumpsGenerationsAndRequestsTheRepoll() {
        started();
        long g = writer.generation("RAD-N");
        long c = writer.contactGeneration("RAD-N");
        publisher.clear();
        store.failNextCommit();

        try (CapturedLogs logs = new CapturedLogs(BoardWriter.class)) {
            BoardResult r = writer.submit(new TrapReceived(new AlarmRaisedNotification("RAD-N", t0, 101, 0, "",
                    Severity.CRITICAL, "Radar failure"))).join();
            assertThat(r.committed()).isFalse();
        }

        assertThat(publisher.all).containsExactly(new RepollRequested("RAD-N", Reason.TRAP));
        assertThat(writer.generation("RAD-N")).isEqualTo(g + 1);
        assertThat(writer.contactGeneration("RAD-N")).isEqualTo(c); // a trap does not invalidate probes
        assertThat(writer.current().board().openAlarms()).isEmpty();
        // a poll that started before the uncommitted trap is now discarded
        publisher.clear();
        assertThat(writer.submit(new PollSucceeded(obs("RAD-N", OPERATIONAL), g)).join()).isEqualTo(BoardResult.ok());
        assertThat(health("RAD-N")).isEqualTo(Health.UNKNOWN);
        assertThat(publisher.all).isEmpty();
        assertThat(stale("poll")).isEqualTo(1.0);
    }

    @Test
    void failedCommitStillPublishesAContactRestoredRepoll() {
        started();
        for (int i = 0; i < 3; i++) {
            writer.submit(probeFailed("RAD-N")).join();
        }
        assertThat(writer.current().board().equipment().get("RAD-N").unknownReason())
                .isEqualTo(UnknownReason.LOST_CONTACT);
        publisher.clear();
        store.failNextCommit();

        try (CapturedLogs logs = new CapturedLogs(BoardWriter.class)) {
            BoardResult r = writer.submit(new ProbeSucceeded(new ProbeObservation("RAD-N", t0, 1000),
                    writer.contactGeneration("RAD-N"))).join();
            assertThat(r.committed()).isFalse();
        }

        assertThat(publisher.events(RepollRequested.class))
                .containsExactly(new RepollRequested("RAD-N", Reason.CONTACT_RESTORED));
    }

    @Test
    void commitFailureOfAContactChangeBumpsBothGenerationsAndMergesTheCounters() {
        started();
        writer.submit(probeFailed("RAD-N")).join();
        writer.submit(probeFailed("RAD-N")).join();
        store.failNextCommit();

        try (CapturedLogs logs = new CapturedLogs(BoardWriter.class)) {
            assertThat(writer.submit(probeFailed("RAD-N")).join().committed()).isFalse();
        }

        assertThat(writer.generation("RAD-N")).isEqualTo(1);
        assertThat(writer.contactGeneration("RAD-N")).isEqualTo(1);
        assertThat(writer.current().telemetry().get("RAD-N").consecutiveFailures()).isEqualTo(3);
        assertThat(writer.current().board().equipment().get("RAD-N").unknownReason())
                .isEqualTo(UnknownReason.NOT_YET_POLLED);
        assertThat(writer.submit(probeFailed("RAD-N")).join().committed()).isTrue();
        assertThat(writer.current().board().equipment().get("RAD-N").unknownReason())
                .isEqualTo(UnknownReason.LOST_CONTACT);
        assertThat(writer.generation("RAD-N")).isEqualTo(2);
        assertThat(writer.contactGeneration("RAD-N")).isEqualTo(2);
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
        assertThat(writer.current().contactGenerations()).isEqualTo(writer.current().generations());
        assertThat(publisher.all).extracting(Object::getClass).containsExactly(BoardCommitted.class, BoardReady.class);
    }

    @Test
    void restartLoadsOpenAlarmsAndMarksSupervisorRestarted() {
        Alarm lost = new Alarm("a1", new AlarmKey("RAD-N", 901, 0), "", Severity.MAJOR, "Lost contact",
                AlarmSource.SUPERVISOR, AlarmState.ACTIVE_UNACK, false, t0.minusSeconds(120), null, null, null, null);
        store.preload(new AlarmEvent(EventType.ALARM_RAISED, t0.minusSeconds(120), lost, Via.SUPERVISOR, null));
        store.preload(new SupervisorEvent(EventType.SUPERVISOR_HEARTBEAT, t0.minusSeconds(60), null));

        started();

        assertThat(writer.current().board().openAlarms()).containsExactly(Map.entry(lost.key(), lost));
        assertThat(writer.current().board().equipment().values())
                .extracting(EquipmentState::health, EquipmentState::unknownReason)
                .containsOnly(tuple(Health.UNKNOWN, UnknownReason.SUPERVISOR_RESTARTED));
        assertThat(((SupervisorEvent) store.events.get(2).event()).reason())
                .isEqualTo(UnknownReason.SUPERVISOR_RESTARTED);
        assertThat(writer.current().board().lastEventId()).isEqualTo(store.events.getLast().id());
    }

    @Test
    void restartKeepsLastKnownMaintenanceAndRole() {
        store.preload(new EquipmentEvent(EventType.EQUIPMENT_STATE_CHANGED, t0.minusSeconds(120),
                new EquipmentState("FDPS-A", Health.OPERATIONAL, null, true, Role.STANDBY, t0.minusSeconds(120),
                        new Contact(true, t0.minusSeconds(120), null))));
        store.preload(new EquipmentEvent(EventType.EQUIPMENT_STATE_CHANGED, t0.minusSeconds(120),
                new EquipmentState("FDPS-B", Health.OPERATIONAL, null, false, Role.ACTIVE, t0.minusSeconds(120),
                        new Contact(true, t0.minusSeconds(120), null))));
        store.preload(new SupervisorEvent(EventType.SUPERVISOR_HEARTBEAT, t0.minusSeconds(60), null));

        started();

        Map<String, EquipmentState> eq = writer.current().board().equipment();
        assertThat(eq.get("FDPS-A")).extracting(EquipmentState::health, EquipmentState::unknownReason,
                EquipmentState::maintenance, EquipmentState::role)
                .containsExactly(Health.UNKNOWN, UnknownReason.SUPERVISOR_RESTARTED, true, Role.STANDBY);
        assertThat(eq.get("FDPS-B")).extracting(EquipmentState::health, EquipmentState::unknownReason,
                EquipmentState::maintenance, EquipmentState::role)
                .containsExactly(Health.UNKNOWN, UnknownReason.SUPERVISOR_RESTARTED, false, Role.ACTIVE);
        ServiceState fd = writer.current().board().services().get(ServiceId.FD);
        assertThat(fd.status()).isEqualTo(ServiceStatus.UNKNOWN);
        assertThat(fd.bestCase()).isEqualTo(ServiceStatus.DEGRADED);
    }

    @Test
    void startUsesTheFoldedBoard() {
        Instant lastPoll = t0.minusSeconds(70);
        store.preload(new EquipmentEvent(EventType.EQUIPMENT_STATE_CHANGED, t0.minusSeconds(600),
                new EquipmentState("RAD-N", Health.OPERATIONAL, null, false, Role.STANDALONE, t0.minusSeconds(600),
                        new Contact(true, lastPoll, null))));
        EquipmentState lost = new EquipmentState("RAD-E", Health.UNKNOWN, UnknownReason.LOST_CONTACT, false,
                Role.STANDALONE, t0.minusSeconds(60), new Contact(false, t0.minusSeconds(75), t0.minusSeconds(60)));
        store.preload(new EquipmentEvent(EventType.CONTACT_LOST, t0.minusSeconds(60), lost));
        store.preload(new SupervisorEvent(EventType.SUPERVISOR_STOPPING, t0.minusSeconds(30), null));

        started();

        Map<String, EquipmentState> eq = writer.current().board().equipment();
        assertThat(eq.get("RAD-N")).as("UNKNOWN since the restart, last contact kept").isEqualTo(new EquipmentState(
                "RAD-N", Health.UNKNOWN, UnknownReason.SUPERVISOR_RESTARTED, false, Role.STANDALONE, t0,
                new Contact(false, lastPoll, null)));
        assertThat(eq.get("RAD-E")).as("already UNKNOWN: its since and contact are kept").isEqualTo(
                new EquipmentState("RAD-E", Health.UNKNOWN, UnknownReason.SUPERVISOR_RESTARTED, false,
                        Role.STANDALONE, t0.minusSeconds(60), lost.contact()));
        assertThat(writer.current().board()).isEqualTo(store.foldedBoard());
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

    @Test
    void aSnapshotIsTakenAfterStartup() {
        started();

        assertThat(snapshots.saved).singleElement().satisfies(s -> {
            assertThat(s.takenAt()).isEqualTo(t0);
            assertThat(s.board()).isEqualTo(writer.current().board());
            assertThat(s.board().lastEventId()).isEqualTo(store.events.getLast().id());
        });
        assertThat(snapshots.threads).containsOnly("board-writer");
    }

    @Test
    void aSnapshotIsTakenOnceTheIntervalHasPassed() {
        started();
        assertThat(snapshots.saved).hasSize(1);

        now.set(t0.plus(Duration.ofMinutes(4)));
        writer.submit(new HeartbeatTick()).join();
        assertThat(snapshots.saved).hasSize(1);

        now.set(t0.plus(Duration.ofMinutes(5)));
        writer.submit(new HeartbeatTick()).join();
        assertThat(snapshots.saved).hasSize(2);
        assertThat(snapshots.saved.getLast().takenAt()).isEqualTo(t0.plus(Duration.ofMinutes(5)));
        assertThat(snapshots.saved.getLast().board()).isEqualTo(writer.current().board());

        now.set(t0.plus(Duration.ofMinutes(9)));
        writer.submit(pollSucceeded("RAD-N", OPERATIONAL)).join();
        assertThat(snapshots.saved).hasSize(2);
    }

    @Test
    void aFailingSnapshotDoesNotFailTheCommit() {
        started();
        snapshots.failing = true;
        now.set(t0.plus(Duration.ofMinutes(5)));

        try (CapturedLogs logs = new CapturedLogs(BoardWriter.class)) {
            BoardResult r = writer.submit(pollSucceeded("RAD-N", OPERATIONAL)).join();

            assertThat(r.committed()).isTrue();
            assertThat(logs.warnings()).singleElement().asString().contains("snapshot");
        }

        assertThat(health("RAD-N")).isEqualTo(Health.OPERATIONAL);
        assertThat(snapshots.saved).hasSize(1);
        assertThat(meters.counter("atm.snapshot.failures").count()).isEqualTo(1.0);
    }

    /** In-memory store assigning ids 1, 2, 3, ...; it records the committing thread and can fail one commit. */
    static final class FakeSnapshots implements SnapshotStore {

        final List<Snapshot> saved = new CopyOnWriteArrayList<>();
        final List<String> threads = new CopyOnWriteArrayList<>();
        volatile boolean failing;

        @Override
        public void save(Board board, Instant takenAt) {
            threads.add(Thread.currentThread().getName());
            if (failing) {
                throw new IllegalStateException("database unavailable");
            }
            saved.add(new Snapshot(takenAt, board));
        }

        @Override
        public java.util.Optional<Snapshot> latestAtOrBefore(Instant at) {
            return saved.stream().filter(s -> !s.takenAt().isAfter(at))
                    .max(java.util.Comparator.comparing(Snapshot::takenAt));
        }
    }

    static final class FakeStore implements BoardStore {

        private final Inventory inv;
        final List<StoredEvent> events = new CopyOnWriteArrayList<>();
        final List<String> commitThreads = new CopyOnWriteArrayList<>();
        private volatile boolean failNext;
        private long nextId = 1;

        FakeStore(Inventory inv) {
            this.inv = inv;
        }

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
        public boolean hasEvents() {
            return !events.isEmpty();
        }

        /** Every stored event folded from the initial board, as the history module rebuilds it. */
        @Override
        public Board foldedBoard() {
            Board board = Board.initial(inv, List.of());
            for (StoredEvent e : events) {
                board = BoardReducer.apply(board, e);
            }
            return board;
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
