package io.github.selvitopu.atmsup.supervisor.board;

import static io.github.selvitopu.atmsup.mib.EquipmentStatus.FAILED;
import static io.github.selvitopu.atmsup.mib.EquipmentStatus.OPERATIONAL;
import static io.github.selvitopu.atmsup.mib.Severity.CRITICAL;
import static io.github.selvitopu.atmsup.supervisor.board.EventType.ALARM_ACKNOWLEDGED;
import static io.github.selvitopu.atmsup.supervisor.board.EventType.ALARM_CLEARED;
import static io.github.selvitopu.atmsup.supervisor.board.EventType.ALARM_RAISED;
import static io.github.selvitopu.atmsup.supervisor.board.EventType.ALARM_UPDATED;
import static io.github.selvitopu.atmsup.supervisor.board.EventType.CONTACT_LOST;
import static io.github.selvitopu.atmsup.supervisor.board.EventType.CONTACT_RESTORED;
import static io.github.selvitopu.atmsup.supervisor.board.EventType.EQUIPMENT_STATE_CHANGED;
import static io.github.selvitopu.atmsup.supervisor.board.EventType.SERVICE_STATE_CHANGED;
import static io.github.selvitopu.atmsup.supervisor.board.EventType.SUPERVISOR_HEARTBEAT;
import static io.github.selvitopu.atmsup.supervisor.board.EventType.SUPERVISOR_STARTED;
import static io.github.selvitopu.atmsup.supervisor.board.EventType.SUPERVISOR_STOPPING;
import static io.github.selvitopu.atmsup.supervisor.board.EventType.TELEMETRY_SAMPLED;
import static io.github.selvitopu.atmsup.supervisor.board.UnknownReason.AWAITING_STATUS;
import static io.github.selvitopu.atmsup.supervisor.board.UnknownReason.NOT_YET_POLLED;
import static io.github.selvitopu.atmsup.supervisor.board.UnknownReason.SUPERVISOR_RESTARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.mib.EquipmentStatus;
import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmEngine;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmKey;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmState;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.AcknowledgeAlarms;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.HeartbeatTick;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.PollFailed;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.PollSucceeded;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.ProbeFailed;
import io.github.selvitopu.atmsup.supervisor.board.BoardEvents.RepollRequested;
import io.github.selvitopu.atmsup.supervisor.board.BoardEvents.RepollRequested.Reason;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.ProbeSucceeded;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.Shutdown;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.Startup;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.TrapReceived;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.AlarmEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.EquipmentEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.ServiceEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.SupervisorEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.TelemetrySampleEvent;
import io.github.selvitopu.atmsup.supervisor.snmp.Notification;
import io.github.selvitopu.atmsup.supervisor.snmp.Notification.AlarmRaisedNotification;
import io.github.selvitopu.atmsup.supervisor.snmp.Notification.StatusChangeNotification;
import io.github.selvitopu.atmsup.supervisor.snmp.Observation;
import io.github.selvitopu.atmsup.supervisor.snmp.ProbeObservation;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BoardDeciderTest {

    private static final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");

    private final Inventory inv = BoardFixtures.inventory(false);
    private final ServiceEvaluator evaluator =
            new ServiceEvaluator(inv, List.of(new SurveillanceRule(), new FlightDataRule()));
    private final AtomicInteger seq = new AtomicInteger();
    private final BoardDecider decider = new BoardDecider(inv, new StatusDeriver(), evaluator,
            new AlarmEngine(() -> "a" + seq.incrementAndGet()), Duration.ofSeconds(30));

    // ---- fixtures

    private LiveBoard initial() {
        return new LiveBoard(Board.initial(inv, List.of()), Map.of(), Map.of(), Map.of());
    }

    private LiveBoard allOperational() {
        return with(Map.of());
    }

    /**
     * Every device OPERATIONAL and reachable since t0 except the given ones (UNKNOWN means NOT_YET_POLLED); services
     * evaluated, no alarms, last full poll at t0, never sampled, all generations 0.
     */
    private LiveBoard with(Map<String, Health> health) {
        Map<String, EquipmentState> eq = new LinkedHashMap<>();
        Map<String, Telemetry> tel = new LinkedHashMap<>();
        Board.initial(inv, List.of()).equipment().forEach((id, s) -> {
            Health h = health.getOrDefault(id, Health.OPERATIONAL);
            eq.put(id, h == Health.UNKNOWN ? s
                    : new EquipmentState(id, h, null, false, s.role(), t0, new Contact(true, t0, null)));
            tel.put(id, new Telemetry(t0, t0, 0, 0, null, Map.of(), Map.of()));
        });
        Board b = new Board(eq, Map.of(), Map.of(), 0);
        return new LiveBoard(new Board(eq, evaluator.evaluate(b, t0), Map.of(), 0), tel, Map.of(), Map.of());
    }

    private static LiveBoard withProbeFailures(LiveBoard lb, String id, int failures) {
        Telemetry t = lb.telemetry().get(id);
        Map<String, Telemetry> tel = new LinkedHashMap<>(lb.telemetry());
        tel.put(id, new Telemetry(t.lastPollAt(), t.lastProbeAt(), failures, t.fullPollFailures(), t.lastSampleAt(),
                t.values(), t.tables()));
        return new LiveBoard(lb.board(), tel, lb.generations(), lb.contactGenerations());
    }

    /** The status generation only (what traps bump). */
    private static LiveBoard withGeneration(LiveBoard lb, String id, long generation) {
        Map<String, Long> gens = new LinkedHashMap<>(lb.generations());
        gens.put(id, generation);
        return new LiveBoard(lb.board(), lb.telemetry(), gens, lb.contactGenerations());
    }

    private static LiveBoard withContactGeneration(LiveBoard lb, String id, long generation) {
        Map<String, Long> gens = new LinkedHashMap<>(lb.contactGenerations());
        gens.put(id, generation);
        return new LiveBoard(lb.board(), lb.telemetry(), lb.generations(), gens);
    }

    /** What the board writer does with a committed decision: apply the events, merge telemetry, bump generations. */
    private static LiveBoard merged(LiveBoard lb, Decision d) {
        Board b = lb.board();
        for (DomainEvent e : d.events()) {
            b = BoardReducer.apply(b, e);
        }
        Map<String, Telemetry> tel = new LinkedHashMap<>(lb.telemetry());
        tel.putAll(d.telemetry());
        Map<String, Long> gens = new LinkedHashMap<>(lb.generations());
        d.invalidated().forEach(id -> gens.merge(id, 1L, Long::sum));
        Map<String, Long> contact = new LinkedHashMap<>(lb.contactGenerations());
        d.contactInvalidated().forEach(id -> contact.merge(id, 1L, Long::sum));
        return new LiveBoard(b, tel, gens, contact);
    }

    /** {@code id} after its third failed probe: LOST_CONTACT with 901 open and both generations 1. */
    private LiveBoard lostContact(String id) {
        LiveBoard lb = withProbeFailures(allOperational(), id, 2);
        return merged(lb, decider.decide(lb, new ProbeFailed(id, t0, "TIMEOUT: no response", 0), t0));
    }

    private Observation obs(String id, EquipmentStatus status) {
        return obs(id, status, Map.of());
    }

    private Observation obs(String id, EquipmentStatus status, Map<String, Object> values) {
        Role role = Board.initial(inv, List.of()).equipment().get(id).role();
        return new Observation(id, t0, status, role, false, values, Map.of(), List.of());
    }

    private static Notification raisedN(String id, int code, Severity severity) {
        return new AlarmRaisedNotification(id, t0, code, 0, "", severity, "Transmitter fault");
    }

    private static AlarmEvent alarmEvent(Decision d, int index) {
        return (AlarmEvent) d.events().get(index);
    }

    // ---- probes and polls

    @Test
    void thirdFailedProbeOrdersEvents() {
        LiveBoard lb = withProbeFailures(allOperational(), "SDPS-A", 2);

        Decision d = decider.decide(lb, new ProbeFailed("SDPS-A", t0, "TIMEOUT: no response", 0), t0);

        assertThat(d.events()).extracting(DomainEvent::type)
                .containsExactly(CONTACT_LOST, ALARM_RAISED, SERVICE_STATE_CHANGED);
        assertThat(alarmEvent(d, 1).alarm().key()).isEqualTo(new AlarmKey("SDPS-A", 901, 0));
        assertThat(((ServiceEvent) d.events().get(2)).state())
                .extracting(ServiceState::id, ServiceState::status, ServiceState::bestCase)
                .containsExactly(ServiceId.SURV, ServiceStatus.UNKNOWN, ServiceStatus.OK);
        assertThat(d.invalidated()).containsExactly("SDPS-A");
        assertThat(d.telemetry().get("SDPS-A").consecutiveFailures()).isEqualTo(3);
        assertThat(d.repoll()).isEmpty();
        assertThat(d.stale()).isFalse();
        assertThat(d.result()).isEqualTo(BoardResult.ok());
    }

    @Test
    void earlierFailedProbesOnlyCount() {
        Decision d = decider.decide(allOperational(), new ProbeFailed("RAD-N", t0, "TIMEOUT: no response", 0), t0);

        assertThat(d.events()).isEmpty();
        assertThat(d.invalidated()).isEmpty();
        assertThat(d.telemetry().get("RAD-N").consecutiveFailures()).isEqualTo(1);
    }

    @Test
    void probeAfterLossClears901ButKeepsHealthUnknown() {
        LiveBoard lb = lostContact("SDPS-A");
        assertThat(lb.board().services().get(ServiceId.SURV).status()).isEqualTo(ServiceStatus.UNKNOWN);
        Instant t1 = t0.plusSeconds(5);

        Decision d = decider.decide(lb, new ProbeSucceeded(new ProbeObservation("SDPS-A", t1, 1000), 1), t1);

        assertThat(d.events()).extracting(DomainEvent::type).containsExactly(CONTACT_RESTORED, ALARM_CLEARED);
        assertThat(((EquipmentEvent) d.events().get(0)).state())
                .extracting(EquipmentState::health, EquipmentState::unknownReason)
                .containsExactly(Health.UNKNOWN, AWAITING_STATUS);
        assertThat(alarmEvent(d, 1).alarm()).extracting(a -> a.key().code(), Alarm::state)
                .containsExactly(901, AlarmState.CLEARED_UNACK);
        assertThat(merged(lb, d).board().services().get(ServiceId.SURV).status()).isEqualTo(ServiceStatus.UNKNOWN);
        assertThat(d.invalidated()).containsExactly("SDPS-A");
        assertThat(d.contactInvalidated()).containsExactly("SDPS-A");
    }

    /** {@code id} lost contact (901 open), then the supervisor restarted: UNKNOWN/SUPERVISOR_RESTARTED, 901 open. */
    private LiveBoard restartedWithOpen901(String id) {
        LiveBoard lb = lostContact(id);
        lb = merged(lb, decider.decide(lb, new Startup(false), t0.plusSeconds(1)));
        assertThat(lb.board().equipment().get(id).unknownReason()).isEqualTo(SUPERVISOR_RESTARTED);
        assertThat(lb.board().openAlarms().get(new AlarmKey(id, 901, 0)).state()).isEqualTo(AlarmState.ACTIVE_UNACK);
        return lb;
    }

    @Test
    void restoredAfterSupervisorRestartClears901() {
        LiveBoard lb = restartedWithOpen901("RAD-N");
        Instant t1 = t0.plusSeconds(5);

        Decision d = decider.decide(lb, new PollSucceeded(obs("RAD-N", OPERATIONAL), lb.generation("RAD-N")), t1);

        assertThat(d.events()).extracting(DomainEvent::type)
                .containsExactly(EQUIPMENT_STATE_CHANGED, ALARM_CLEARED, SERVICE_STATE_CHANGED, TELEMETRY_SAMPLED);
        assertThat(alarmEvent(d, 1).alarm()).extracting(Alarm::key, Alarm::state)
                .containsExactly(new AlarmKey("RAD-N", 901, 0), AlarmState.CLEARED_UNACK);
        assertThat(d.invalidated()).isEmpty();
    }

    @Test
    void probeThatRestoresContactAsksForAVerificationPoll() {
        LiveBoard lb = lostContact("RAD-N");
        assertThat(lb.board().equipment().get("RAD-N").unknownReason()).isEqualTo(UnknownReason.LOST_CONTACT);
        Instant t1 = t0.plusSeconds(5);

        Decision d = decider.decide(lb, new ProbeSucceeded(new ProbeObservation("RAD-N", t1, 1000), 1), t1);

        assertThat(d.repoll()).containsExactly(new RepollRequested("RAD-N", Reason.CONTACT_RESTORED));
        assertThat(d.invalidated()).contains("RAD-N");
    }

    @Test
    void probeWithoutContactChangeAsksForNoPoll() {
        Instant t1 = t0.plusSeconds(5);

        Decision d = decider.decide(allOperational(),
                new ProbeSucceeded(new ProbeObservation("RAD-N", t1, 1000), 0), t1);

        assertThat(d.repoll()).isEmpty();
    }

    @Test
    void probeAfterSupervisorRestartClears901() {
        LiveBoard lb = restartedWithOpen901("RAD-N");
        Instant t1 = t0.plusSeconds(5);

        Decision d = decider.decide(lb,
                new ProbeSucceeded(new ProbeObservation("RAD-N", t1, 1000), lb.contactGeneration("RAD-N")), t1);

        assertThat(d.events()).extracting(DomainEvent::type).containsExactly(ALARM_CLEARED);
        assertThat(alarmEvent(d, 0).alarm()).extracting(Alarm::key, Alarm::state)
                .containsExactly(new AlarmKey("RAD-N", 901, 0), AlarmState.CLEARED_UNACK);
        assertThat(merged(lb, d).board().equipment().get("RAD-N").unknownReason()).isEqualTo(SUPERVISOR_RESTARTED);
    }

    @Test
    void cleared901IsNotTouchedAgain() {
        LiveBoard lb = lostContact("RAD-N");
        Instant t1 = t0.plusSeconds(5);
        lb = merged(lb, decider.decide(lb, new ProbeSucceeded(new ProbeObservation("RAD-N", t1, 1000), 1), t1));
        assertThat(lb.board().openAlarms().get(new AlarmKey("RAD-N", 901, 0)).state())
                .isEqualTo(AlarmState.CLEARED_UNACK);

        Decision d = decider.decide(lb, new PollSucceeded(obs("RAD-N", OPERATIONAL), lb.generation("RAD-N")),
                t1.plusSeconds(5));

        assertThat(d.events()).extracting(DomainEvent::type).containsExactly(EQUIPMENT_STATE_CHANGED,
                TELEMETRY_SAMPLED);
    }

    @Test
    void staleSuccessIsDiscarded() {
        LiveBoard lb = withGeneration(lostContact("RAD-N"), "RAD-N", 2);

        Decision d = decider.decide(lb, new PollSucceeded(obs("RAD-N", OPERATIONAL), 1), t0);

        assertThat(d.stale()).isTrue();
        assertThat(d.events()).isEmpty();
        assertThat(d.telemetry()).isEmpty();
        assertThat(d.invalidated()).isEmpty();
        assertThat(d.repoll()).isEmpty();
        assertThat(d.result()).isEqualTo(BoardResult.ok());
        assertThat(d).isEqualTo(Decision.discarded());
    }

    @Test
    void staleFailureIsDiscarded() {
        LiveBoard lb = withContactGeneration(withProbeFailures(allOperational(), "RAD-N", 2), "RAD-N", 2);

        Decision d = decider.decide(lb, new ProbeFailed("RAD-N", t0, "TIMEOUT: no response", 1), t0);

        assertThat(d.stale()).isTrue();
        assertThat(d.events()).isEmpty();
        assertThat(merged(lb, d).telemetry().get("RAD-N").consecutiveFailures()).isEqualTo(2);
    }

    @Test
    void resultOfTheCurrentGenerationIsApplied() {
        LiveBoard lb = withGeneration(allOperational(), "RAD-N", 2);

        Decision d = decider.decide(lb, new PollFailed("RAD-N", t0, "TIMEOUT: no response", 2), t0);

        assertThat(d.stale()).isFalse();
        assertThat(d.telemetry().get("RAD-N").fullPollFailures()).isEqualTo(1);
    }

    @Test
    void contactChangeInvalidatesBothGenerations() {
        LiveBoard lb = withProbeFailures(allOperational(), "RAD-N", 2);

        Decision d = decider.decide(lb, new ProbeFailed("RAD-N", t0, "TIMEOUT: no response", 0), t0);

        assertThat(d.events()).extracting(DomainEvent::type).startsWith(CONTACT_LOST);
        assertThat(d.invalidated()).isEqualTo(Set.of("RAD-N"));
        assertThat(d.contactInvalidated()).isEqualTo(Set.of("RAD-N"));
    }

    @Test
    void failedSdpsPairRaisesServiceAlarms() {
        LiveBoard lb = allOperational();
        lb = merged(lb, decider.decide(lb, new PollSucceeded(obs("SDPS-B", FAILED), 0), t0));
        assertThat(lb.board().openAlarms()).containsKey(new AlarmKey("SURV", 903, 0));
        Instant t1 = t0.plusSeconds(5);

        Decision d = decider.decide(lb, new PollSucceeded(obs("SDPS-A", FAILED), 0), t1);

        assertThat(d.events()).extracting(DomainEvent::type)
                .containsExactly(EQUIPMENT_STATE_CHANGED, SERVICE_STATE_CHANGED, ALARM_RAISED, TELEMETRY_SAMPLED);
        assertThat(((ServiceEvent) d.events().get(1)).state())
                .extracting(ServiceState::id, ServiceState::status)
                .containsExactly(ServiceId.SURV, ServiceStatus.FAILED);
        assertThat(alarmEvent(d, 2).alarm()).extracting(Alarm::key, Alarm::text)
                .containsExactly(new AlarmKey("SURV", 904, 0), "Service failed: Surveillance");
        assertThat(merged(lb, d).board().openAlarms())
                .containsKeys(new AlarmKey("SURV", 903, 0), new AlarmKey("SURV", 904, 0));
    }

    @Test
    void anUnknownSdpsBesideAConfirmedDegradationRaisesNo903() {
        LiveBoard lb = with(Map.of("RAD-N", Health.FAILED, "RAD-E", Health.FAILED));
        assertThat(lb.board().services().get(ServiceId.SURV).status()).isEqualTo(ServiceStatus.DEGRADED);
        assertThat(lb.board().openAlarms()).doesNotContainKey(new AlarmKey("SURV", 903, 0));
        lb = withProbeFailures(lb, "SDPS-B", 2);

        Decision d = decider.decide(lb, new ProbeFailed("SDPS-B", t0, "TIMEOUT: no response", 0), t0);

        assertThat(merged(lb, d).board().openAlarms()).doesNotContainKey(new AlarmKey("SURV", 903, 0));
        assertThat(d.events()).filteredOn(e -> e instanceof ServiceEvent)
                .map(e -> ((ServiceEvent) e).state())
                .anySatisfy(st -> {
                    assertThat(st.id()).isEqualTo(ServiceId.SURV);
                    assertThat(st.redundancyLost()).isFalse();
                });
    }

    @Test
    void serviceFailedAlarmComesBeforeRedundancyLost() {
        LiveBoard lb = with(Map.of("SDPS-A", Health.UNKNOWN, "SDPS-B", Health.FAILED));
        assertThat(lb.board().services().get(ServiceId.SURV).status()).isEqualTo(ServiceStatus.UNKNOWN);

        Decision d = decider.decide(lb, new PollSucceeded(obs("SDPS-A", FAILED), 0), t0);

        assertThat(d.events()).extracting(DomainEvent::type).containsExactly(EQUIPMENT_STATE_CHANGED,
                SERVICE_STATE_CHANGED, ALARM_RAISED, ALARM_RAISED, TELEMETRY_SAMPLED);
        assertThat(List.of(alarmEvent(d, 2), alarmEvent(d, 3))).extracting(e -> e.alarm().key().code())
                .containsExactly(904, 903);
    }

    @Test
    void maintenanceUpdatesComeBeforeContactAlarms() {
        LiveBoard lb = lostContact("RAD-N");
        Instant t1 = t0.plusSeconds(5);
        var inMaintenance = new Observation("RAD-N", t1, OPERATIONAL, Role.STANDALONE, true, Map.of(), Map.of(), List.of());

        Decision d = decider.decide(lb, new PollSucceeded(inMaintenance, 1), t1);

        assertThat(d.events()).extracting(DomainEvent::type)
                .containsExactly(CONTACT_RESTORED, ALARM_UPDATED, ALARM_CLEARED, TELEMETRY_SAMPLED);
        assertThat(alarmEvent(d, 1).alarm().suppressed()).isTrue();
        assertThat(alarmEvent(d, 2).alarm().state()).isEqualTo(AlarmState.CLEARED_UNACK);
        assertThat(d.invalidated()).containsExactly("RAD-N");
    }

    // ---- telemetry samples (amendment §5)

    @Test
    void stableTelemetryIsSampledEvery30Seconds() {
        LiveBoard lb = allOperational();

        Decision d1 = decider.decide(lb,
                new PollSucceeded(obs("RAD-N", OPERATIONAL, Map.of("plotsPerScan", 300L)), 0), t0);

        assertThat(d1.events()).extracting(DomainEvent::type).containsExactly(TELEMETRY_SAMPLED);
        assertThat((TelemetrySampleEvent) d1.events().get(0))
                .isEqualTo(new TelemetrySampleEvent(t0, "RAD-N", t0, Map.of("plotsPerScan", 300L), Map.of()));
        assertThat(d1.telemetry().get("RAD-N").lastSampleAt()).isEqualTo(t0);
        lb = merged(lb, d1);
        Decision d2 = decider.decide(lb,
                new PollSucceeded(obs("RAD-N", OPERATIONAL, Map.of("plotsPerScan", 300L)), 0), t0.plusSeconds(10));
        assertThat(d2.events()).isEmpty();
        assertThat(d2.telemetry().get("RAD-N").lastSampleAt()).isEqualTo(t0);
        assertThat(decider.decide(lb,
                new PollSucceeded(obs("RAD-N", OPERATIONAL, Map.of("plotsPerScan", 300L)), 0), t0.plusSeconds(30))
                .events()).extracting(DomainEvent::type).containsExactly(TELEMETRY_SAMPLED);
    }

    @Test
    void failuresAndProbesNeverProduceSamples() {
        LiveBoard lb = allOperational();
        lb = merged(lb, decider.decide(lb, new PollSucceeded(obs("RAD-N", OPERATIONAL), 0), t0));
        Instant later = t0.plusSeconds(40);

        for (BoardInput in : List.of(new PollFailed("RAD-N", later, "TIMEOUT: no response", 0),
                new ProbeSucceeded(new ProbeObservation("RAD-N", later, 1000), 0),
                new ProbeFailed("RAD-N", later, "TIMEOUT: no response", 0))) {
            Decision d = decider.decide(lb, in, later);
            assertThat(d.events()).extracting(DomainEvent::type).doesNotContain(TELEMETRY_SAMPLED);
            assertThat(d.telemetry().get("RAD-N").lastSampleAt()).isEqualTo(t0);
        }
    }

    // ---- traps and acknowledgements

    @Test
    void trapRaisesAlarmRequestsRepollAndInvalidates() {
        Decision d = decider.decide(allOperational(), new TrapReceived(raisedN("RAD-N", 101, CRITICAL)), t0);

        assertThat(d.events()).extracting(DomainEvent::type).containsExactly(ALARM_RAISED);
        assertThat(alarmEvent(d, 0).alarm()).extracting(Alarm::key, Alarm::severity)
                .containsExactly(new AlarmKey("RAD-N", 101, 0), CRITICAL);
        assertThat(d.repoll()).containsExactly(new RepollRequested("RAD-N", Reason.TRAP));
        assertThat(d.invalidated()).containsExactly("RAD-N");
        assertThat(d.telemetry()).isEmpty();
    }

    @Test
    void statusChangeTrapRequestsRepollAndInvalidates() {
        Decision d = decider.decide(allOperational(), new TrapReceived(new StatusChangeNotification("RAD-N", t0)),
                t0);

        assertThat(d.events()).isEmpty();
        assertThat(d.repoll()).containsExactly(new RepollRequested("RAD-N", Reason.TRAP));
        assertThat(d.invalidated()).containsExactly("RAD-N");
    }

    @Test
    void trapInvalidatesOnlyTheStatusGeneration() {
        Decision d = decider.decide(allOperational(), new TrapReceived(raisedN("RAD-N", 101, CRITICAL)), t0);

        assertThat(d.invalidated()).isEqualTo(Set.of("RAD-N"));
        assertThat(d.contactInvalidated()).isEmpty();
    }

    @Test
    void trapInMaintenanceRaisesSuppressedAlarm() {
        LiveBoard lb = allOperational();
        var inMaintenance = new Observation("RAD-N", t0, OPERATIONAL, Role.STANDALONE, true, Map.of(), Map.of(), List.of());
        lb = merged(lb, decider.decide(lb, new PollSucceeded(inMaintenance, 0), t0));

        Decision d = decider.decide(lb, new TrapReceived(raisedN("RAD-N", 101, CRITICAL)), t0);

        assertThat(alarmEvent(d, 0).alarm().suppressed()).isTrue();
    }

    @Test
    void acknowledgeReturnsAcknowledgedAndNotFound() {
        LiveBoard lb = lostContact("RAD-N");
        String id = lb.board().openAlarms().get(new AlarmKey("RAD-N", 901, 0)).id();

        Decision d = decider.decide(lb, new AcknowledgeAlarms(List.of(id, "missing"), "seen", "demo-user"), t0);

        assertThat(d.events()).extracting(DomainEvent::type).containsExactly(ALARM_ACKNOWLEDGED);
        assertThat(alarmEvent(d, 0).actor()).isEqualTo("demo-user");
        assertThat(d.result().committed()).isTrue();
        assertThat(d.result().acknowledged()).extracting(Alarm::id, Alarm::state, Alarm::ackComment)
                .containsExactly(tuple(id, AlarmState.ACTIVE_ACK, "seen"));
        assertThat(d.result().notFound()).containsExactly("missing");
        assertThat(d.invalidated()).isEmpty();
    }

    // ---- supervisor lifecycle

    @Test
    void startupFirstTimeIsNotYetPolled() {
        Decision d = decider.decide(initial(), new Startup(true), t0);

        assertThat(((SupervisorEvent) d.events().get(0)).reason()).isEqualTo(NOT_YET_POLLED);
        assertThat(d.events()).extracting(DomainEvent::type)
                .containsExactly(SUPERVISOR_STARTED, SERVICE_STATE_CHANGED, SERVICE_STATE_CHANGED);
        assertThat(d.events().subList(1, 3)).extracting(e -> ((ServiceEvent) e).state())
                .extracting(ServiceState::id, ServiceState::status)
                .containsExactly(tuple(ServiceId.SURV, ServiceStatus.UNKNOWN),
                        tuple(ServiceId.FD, ServiceStatus.UNKNOWN));
    }

    @Test
    void restartIsSupervisorRestartedAndRepublishesEveryService() {
        Decision d = decider.decide(allOperational(), new Startup(false), t0);

        assertThat(((SupervisorEvent) d.events().get(0)).reason()).isEqualTo(SUPERVISOR_RESTARTED);
        assertThat(d.events()).extracting(DomainEvent::type)
                .containsExactly(SUPERVISOR_STARTED, SERVICE_STATE_CHANGED, SERVICE_STATE_CHANGED);
    }

    @Test
    void heartbeatAndShutdownAreSupervisorEvents() {
        assertThat(decider.decide(allOperational(), new HeartbeatTick(), t0).events())
                .containsExactly(new SupervisorEvent(SUPERVISOR_HEARTBEAT, t0, null));
        assertThat(decider.decide(allOperational(), new Shutdown(), t0).events())
                .containsExactly(new SupervisorEvent(SUPERVISOR_STOPPING, t0, null));
    }
}
