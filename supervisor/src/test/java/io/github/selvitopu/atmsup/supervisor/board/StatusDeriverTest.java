package io.github.selvitopu.atmsup.supervisor.board;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.selvitopu.atmsup.mib.EquipmentStatus;
import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.EquipmentEvent;
import io.github.selvitopu.atmsup.supervisor.snmp.Observation;
import io.github.selvitopu.atmsup.supervisor.snmp.ProbeObservation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StatusDeriverTest {

    private static final Duration FIVE_S = Duration.ofSeconds(5);
    private static final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant t1 = t0.plusSeconds(5);
    private static final Instant t2 = t0.plusSeconds(10);
    private static final Instant t3 = t0.plusSeconds(15);
    private static final Instant t4 = t0.plusSeconds(20);
    private static final Instant t5 = t0.plusSeconds(25);

    private final StatusDeriver deriver = new StatusDeriver();

    private static EquipmentState initial(String id) {
        return BoardFixtures.unknown(id, UnknownReason.NOT_YET_POLLED);
    }

    private static EquipmentState operational(String id) {
        return new EquipmentState(id, Health.OPERATIONAL, null, false, Role.STANDALONE, t0,
                new Contact(true, t0, null));
    }

    private static EquipmentState lostContact(String id) {
        return new EquipmentState(id, Health.UNKNOWN, UnknownReason.LOST_CONTACT, false, Role.STANDALONE, t0,
                new Contact(false, t0, t3));
    }

    private static Telemetry tel(Instant lastProbe, int failures) {
        return new Telemetry(lastProbe, lastProbe, failures, 0, null, Map.of(), Map.of());
    }

    private static Telemetry telLastPoll(Instant lastPoll) {
        return new Telemetry(lastPoll, lastPoll, 0, 0, null, Map.of(), Map.of());
    }

    private static Observation obs(EquipmentStatus status) {
        return new Observation("RAD-N", t0, status, Role.STANDALONE, false, Map.of("rate", 12),
                Map.of("rows", List.of(Map.of("a", 1))), List.of());
    }

    private static ProbeObservation probe() {
        return new ProbeObservation("RAD-N", t0, 1000);
    }

    @Test
    void firstFullPollEmitsStateChanged() {
        var d = deriver.pollSucceeded(initial("RAD-N"), Telemetry.EMPTY, obs(EquipmentStatus.OPERATIONAL), t0);
        assertThat(d.event()).get().extracting(EquipmentEvent::type).isEqualTo(EventType.EQUIPMENT_STATE_CHANGED);
        assertThat(d.event().get().state().health()).isEqualTo(Health.OPERATIONAL);
        assertThat(d.event().get().state().unknownReason()).isNull();
        assertThat(d.event().get().state().healthSince()).isEqualTo(t0);
        assertThat(d.event().get().state().contact()).isEqualTo(new Contact(true, t0, null));
        assertThat(d.telemetry().lastPollAt()).isEqualTo(t0);
        assertThat(d.telemetry().values()).containsEntry("rate", 12);
        assertThat(d.telemetry().tables()).containsKey("rows");
    }

    @Test
    void unchangedPollEmitsNothingAndKeepsSampleTime() {
        var before = new Telemetry(t0, t0, 2, 1, t0, Map.of(), Map.of());
        var d = deriver.pollSucceeded(operational("RAD-N"), before, obs(EquipmentStatus.OPERATIONAL), t1);
        assertThat(d.event()).isEmpty();
        assertThat(d.telemetry().lastProbeAt()).isEqualTo(t1);
        assertThat(d.telemetry().consecutiveFailures()).isZero();
        assertThat(d.telemetry().fullPollFailures()).isZero();
        assertThat(d.telemetry().lastSampleAt()).isEqualTo(t0);
    }

    @Test
    void changedHealthKeepsNothingStale() {
        var d = deriver.pollSucceeded(operational("RAD-N"), tel(t0, 0), obs(EquipmentStatus.DEGRADED), t1);
        assertThat(d.event().get().state().health()).isEqualTo(Health.DEGRADED);
        assertThat(d.event().get().state().healthSince()).isEqualTo(t1);
    }

    @Test
    void maintenanceOrRoleChangeEmitsEventAndKeepsHealthSince() {
        var o = new Observation("RAD-N", t1, EquipmentStatus.OPERATIONAL, Role.STANDALONE, true, Map.of(), Map.of(), List.of());
        var d = deriver.pollSucceeded(operational("RAD-N"), tel(t0, 0), o, t1);
        assertThat(d.event().get().state().maintenance()).isTrue();
        assertThat(d.event().get().state().healthSince()).isEqualTo(t0);
    }

    @Test
    void thirdFailedProbeLosesContactOnce() {
        var d1 = deriver.probeFailed(operational("RAD-N"), tel(t0, 0), t1);
        var d2 = deriver.probeFailed(operational("RAD-N"), d1.telemetry(), t2);
        var d3 = deriver.probeFailed(operational("RAD-N"), d2.telemetry(), t3);
        assertThat(d1.event()).isEmpty();
        assertThat(d2.event()).isEmpty();
        assertThat(d3.event().get().type()).isEqualTo(EventType.CONTACT_LOST);
        assertThat(d3.event().get().state()).extracting(EquipmentState::health, EquipmentState::unknownReason)
                .containsExactly(Health.UNKNOWN, UnknownReason.LOST_CONTACT);
        assertThat(d3.event().get().state().contact()).isEqualTo(new Contact(false, t0, t3));
        assertThat(d3.event().get().state().healthSince()).isEqualTo(t3);
        assertThat(deriver.probeFailed(d3.event().get().state(), d3.telemetry(), t4).event()).isEmpty();
    }

    @Test
    void probeAfterLossRestoresContactButNotHealth() {
        var d = deriver.probeSucceeded(lostContact("RAD-N"), tel(t0, 3), probe(), FIVE_S, t5);
        assertThat(d.event().get().type()).isEqualTo(EventType.CONTACT_RESTORED);
        assertThat(d.event().get().state()).extracting(EquipmentState::health, EquipmentState::unknownReason)
                .containsExactly(Health.UNKNOWN, UnknownReason.AWAITING_STATUS);
        assertThat(d.event().get().state().contact()).isEqualTo(new Contact(true, t5, null));
        assertThat(d.telemetry().consecutiveFailures()).isZero();
        assertThat(d.telemetry().lastProbeAt()).isEqualTo(t5);
    }

    @Test
    void fullPollAfterAwaitingStatusSetsHealth() {
        var awaiting = BoardFixtures.unknown("RAD-N", UnknownReason.AWAITING_STATUS);
        var d = deriver.pollSucceeded(awaiting, tel(t0, 0), obs(EquipmentStatus.OPERATIONAL), t5);
        assertThat(d.event().get().type()).isEqualTo(EventType.EQUIPMENT_STATE_CHANGED);
        assertThat(d.event().get().state().health()).isEqualTo(Health.OPERATIONAL);
        assertThat(d.event().get().state().unknownReason()).isNull();
        assertThat(d.event().get().state().contact().reachable()).isTrue();
    }

    @Test
    void fullPollDirectlyAfterLostContactRestoresContactWithKnownHealth() {
        var d = deriver.pollSucceeded(lostContact("RAD-N"), tel(t0, 3), obs(EquipmentStatus.DEGRADED), t5);
        assertThat(d.event().get().type()).isEqualTo(EventType.CONTACT_RESTORED);
        assertThat(d.event().get().state().health()).isEqualTo(Health.DEGRADED);
        assertThat(d.event().get().state().contact()).isEqualTo(new Contact(true, t5, null));
    }

    @Test
    void probesOkButFullPollsFailingBecomeStatusUnavailable() {
        var cur = operational("RAD-N");
        var tel = telLastPoll(t0);
        assertThat(deriver.probeSucceeded(cur, tel, probe(), FIVE_S, t0.plusSeconds(15)).event()).isEmpty();
        var d = deriver.probeSucceeded(cur, tel, probe(), FIVE_S, t0.plusSeconds(16));
        assertThat(d.event().get().state().unknownReason()).isEqualTo(UnknownReason.STATUS_UNAVAILABLE);
        assertThat(d.event().get().state().health()).isEqualTo(Health.UNKNOWN);
        assertThat(d.event().get().state().healthSince()).isEqualTo(t0.plusSeconds(16));
        assertThat(d.event().get().type()).isEqualTo(EventType.EQUIPMENT_STATE_CHANGED);
    }

    @Test
    void probeOnAlreadyUnavailableStatusEmitsNothingMore() {
        var cur = BoardFixtures.unknown("RAD-N", UnknownReason.STATUS_UNAVAILABLE);
        assertThat(deriver.probeSucceeded(cur, telLastPoll(t0), probe(), FIVE_S, t0.plusSeconds(60)).event())
                .isEmpty();
    }

    @Test
    void probeNeverSetsHealthOnNotYetPolled() {
        assertThat(deriver.probeSucceeded(initial("RAD-N"), Telemetry.EMPTY, probe(), FIVE_S, t1).event()).isEmpty();
    }

    @Test
    void fullPollFailureNeverTouchesContact() {
        var d = deriver.pollFailed(operational("RAD-N"), tel(t0, 0), t1);
        assertThat(d.event()).isEmpty();
        assertThat(d.telemetry().consecutiveFailures()).isZero();
        assertThat(d.telemetry().fullPollFailures()).isEqualTo(1);
        assertThat(d.telemetry().lastPollAt()).isEqualTo(t0);
    }

    @Test
    void neverReachedDeviceLosesContactAfterThreeFailedProbes() {
        var d1 = deriver.probeFailed(initial("RAD-N"), Telemetry.EMPTY, t1);
        var d2 = deriver.probeFailed(initial("RAD-N"), d1.telemetry(), t2);
        var d3 = deriver.probeFailed(initial("RAD-N"), d2.telemetry(), t3);
        assertThat(d1.event()).isEmpty();
        assertThat(d2.event()).isEmpty();
        assertThat(d3.event().get().type()).isEqualTo(EventType.CONTACT_LOST);
        assertThat(d3.event().get().state().unknownReason()).isEqualTo(UnknownReason.LOST_CONTACT);
        assertThat(d3.event().get().state().contact()).isEqualTo(new Contact(false, null, t3));
    }
}
