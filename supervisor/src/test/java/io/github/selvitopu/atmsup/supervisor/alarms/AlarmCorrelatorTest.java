package io.github.selvitopu.atmsup.supervisor.alarms;

import static io.github.selvitopu.atmsup.mib.Severity.CRITICAL;
import static io.github.selvitopu.atmsup.mib.Severity.MAJOR;
import static io.github.selvitopu.atmsup.mib.Severity.MINOR;
import static io.github.selvitopu.atmsup.mib.Severity.WARNING;
import static io.github.selvitopu.atmsup.supervisor.alarms.AlarmEventType.ACKNOWLEDGED;
import static io.github.selvitopu.atmsup.supervisor.alarms.AlarmEventType.CLEARED;
import static io.github.selvitopu.atmsup.supervisor.alarms.AlarmEventType.RAISED;
import static io.github.selvitopu.atmsup.supervisor.alarms.AlarmEventType.UPDATED;
import static io.github.selvitopu.atmsup.supervisor.alarms.AlarmSource.DEVICE;
import static io.github.selvitopu.atmsup.supervisor.alarms.AlarmSource.SUPERVISOR;
import static io.github.selvitopu.atmsup.supervisor.alarms.AlarmState.ACTIVE_ACK;
import static io.github.selvitopu.atmsup.supervisor.alarms.AlarmState.ACTIVE_UNACK;
import static io.github.selvitopu.atmsup.supervisor.alarms.AlarmState.CLEARED_UNACK;
import static io.github.selvitopu.atmsup.supervisor.alarms.CorrelationRule.SAME_UNIT;
import static io.github.selvitopu.atmsup.supervisor.alarms.CorrelationRule.SENSOR_INPUT;
import static io.github.selvitopu.atmsup.supervisor.alarms.CorrelationRule.SWITCHOVER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.simulator.fixture.TestInventories;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AlarmCorrelatorTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    private final AlarmCorrelator correlator = new AlarmCorrelator(new CorrelationTopology(
            Map.of("SDPS-A", "SDPS-B", "SDPS-B", "SDPS-A", "FDPS-A", "FDPS-B", "FDPS-B", "FDPS-A"),
            Set.of("RAD-N", "RAD-E", "RAD-S")));

    private static Alarm alarm(String id, String eq, int code, int instance, String instanceName, Severity sev,
                               AlarmState state) {
        return new Alarm(id, new AlarmKey(eq, code, instance), instanceName, sev, "text " + code, DEVICE, state,
                false, T0, null, null, null, state == CLEARED_UNACK ? T0 : null);
    }

    private static Alarm failure(String id, String eq, Severity sev) {
        return alarm(id, eq, 101, 0, "", sev, ACTIVE_UNACK);
    }

    private static Alarm input(String id, String eq, String sensor) {
        return alarm(id, eq, 401, 1, sensor, MINOR, ACTIVE_UNACK);
    }

    private static Map<AlarmKey, Alarm> open(Alarm... alarms) {
        Map<AlarmKey, Alarm> m = new LinkedHashMap<>();
        for (Alarm a : alarms) {
            m.put(a.key(), a);
        }
        return m;
    }

    private static AlarmChange raised(Alarm a) {
        return new AlarmChange(RAISED, a, Via.TRAP, null);
    }

    private static AlarmChange updated(Alarm a) {
        return new AlarmChange(UPDATED, a, Via.TRAP, null);
    }

    private static Correlation link(String causeId, String eq, CorrelationRule rule) {
        return new Correlation(causeId, eq, 101, rule);
    }

    private Alarm raisedAlarm(Map<AlarmKey, Alarm> open, Alarm a) {
        var out = correlator.apply(open, raised(a));
        assertThat(out).hasSize(1);
        return out.get(0).alarm();
    }

    @Test
    void sensorInputLostLinksToTheFailedRadar() {
        var open = open(failure("c1", "RAD-N", CRITICAL));
        var a = raisedAlarm(open, input("a1", "SDPS-A", "RAD-N"));
        assertThat(a.correlation()).isEqualTo(link("c1", "RAD-N", SENSOR_INPUT));
    }

    @Test
    void switchoverLinksToTheFailedPartner() {
        var open = open(failure("c1", "FDPS-A", CRITICAL));
        var a = raisedAlarm(open, alarm("a1", "FDPS-B", 104, 0, "", WARNING, ACTIVE_UNACK));
        assertThat(a.correlation()).isEqualTo(link("c1", "FDPS-A", SWITCHOVER));
    }

    @Test
    void aUnitsOwnAlarmLinksToItsFailure() {
        var open = open(failure("c1", "RAD-N", CRITICAL));
        var a = raisedAlarm(open, alarm("a1", "RAD-N", 201, 0, "", MAJOR, ACTIVE_UNACK));
        assertThat(a.correlation()).isEqualTo(link("c1", "RAD-N", SAME_UNIT));
    }

    @Test
    void theShapeAloneChoosesTheRule() {
        var open = open(failure("c1", "SDPS-A", CRITICAL));
        assertThat(raisedAlarm(open, input("a1", "SDPS-A", "RAD-N")).correlation()).isNull();
    }

    @Test
    void aSwitchoverWithoutAFailedPartnerStandsAlone() {
        var open = open(failure("c1", "FDPS-B", CRITICAL));
        assertThat(raisedAlarm(open, alarm("a1", "FDPS-B", 104, 0, "", WARNING, ACTIVE_UNACK)).correlation())
                .isNull();
    }

    @Test
    void failureAndMaintenanceAlarmsAreNeverCorrelated() {
        var open = open(failure("c1", "RAD-N", CRITICAL));
        assertThat(raisedAlarm(open, alarm("a1", "RAD-N", 101, 1, "", MINOR, ACTIVE_UNACK)).correlation()).isNull();
        assertThat(raisedAlarm(open, alarm("a2", "RAD-N", 103, 0, "", MINOR, ACTIVE_UNACK)).correlation()).isNull();
    }

    @Test
    void supervisorAlarmsAreNeverCorrelatedOrCauses() {
        var open = open(failure("c1", "RAD-N", CRITICAL));
        var lost = new Alarm("a1", new AlarmKey("RAD-N", 901, 0), "", MINOR, "lost", SUPERVISOR, ACTIVE_UNACK,
                false, T0, null, null, null, null);
        assertThat(raisedAlarm(open, lost).correlation()).isNull();

        var supervisorFailure = new Alarm("s1", new AlarmKey("RAD-E", 101, 0), "", CRITICAL, "x", SUPERVISOR,
                ACTIVE_UNACK, false, T0, null, null, null, null);
        var open2 = open(supervisorFailure);
        assertThat(raisedAlarm(open2, alarm("a2", "RAD-E", 201, 0, "", MINOR, ACTIVE_UNACK)).correlation())
                .isNull();
    }

    @Test
    void aMoreSevereAlarmIsNotCorrelated() {
        var open = open(failure("c1", "RAD-N", MAJOR));
        assertThat(raisedAlarm(open, alarm("a1", "RAD-N", 201, 0, "", CRITICAL, ACTIVE_UNACK)).correlation())
                .isNull();
    }

    @Test
    void aClearedCauseIsNoCause() {
        var open = open(alarm("c1", "RAD-N", 101, 0, "", CRITICAL, CLEARED_UNACK));
        assertThat(raisedAlarm(open, input("a1", "SDPS-A", "RAD-N")).correlation()).isNull();
    }

    @Test
    void aLateCauseAdoptsItsAlarms() {
        var open = open(input("a1", "SDPS-A", "RAD-N"), input("a2", "SDPS-B", "RAD-N"),
                alarm("a3", "RAD-N", 201, 0, "", MAJOR, ACTIVE_UNACK),
                alarm("a4", "RAD-N", 202, 0, "", MINOR, ACTIVE_ACK));
        var out = correlator.apply(open, raised(failure("c1", "RAD-N", CRITICAL)));
        assertThat(out).extracting(c -> c.type(), c -> c.via(), c -> c.actor(), c -> c.alarm().id(),
                        c -> c.alarm().correlation() == null ? null : c.alarm().correlation().rule())
                .containsExactly(tuple(RAISED, Via.TRAP, null, "c1", null),
                        tuple(UPDATED, Via.CORRELATION, null, "a3", SAME_UNIT),
                        tuple(UPDATED, Via.CORRELATION, null, "a4", SAME_UNIT),
                        tuple(UPDATED, Via.CORRELATION, null, "a1", SENSOR_INPUT),
                        tuple(UPDATED, Via.CORRELATION, null, "a2", SENSOR_INPUT));
        assertThat(out.get(1).alarm().correlation()).isEqualTo(link("c1", "RAD-N", SAME_UNIT));
        assertThat(open.get(new AlarmKey("RAD-N", 201, 0)).correlation()).isNull();
    }

    @Test
    void adoptionSkipsAlarmsLinkedToAnotherActiveCause() {
        var linked = input("a1", "SDPS-A", "RAD-N").withCorrelation(link("c0", "RAD-N", SENSOR_INPUT));
        var otherCause = alarm("c0", "RAD-N", 101, 5, "", CRITICAL, ACTIVE_UNACK);
        var open = open(linked, otherCause);
        // the cause of another instance is the active DEVICE 101 the link names
        var out = correlator.apply(open, raised(failure("c1", "RAD-N", CRITICAL)));
        assertThat(out).hasSize(1);
    }

    @Test
    void adoptionSkipsClearedAlarms() {
        var open = open(alarm("a1", "RAD-N", 201, 0, "", MAJOR, CLEARED_UNACK));
        assertThat(correlator.apply(open, raised(failure("c1", "RAD-N", CRITICAL)))).hasSize(1);
    }

    @Test
    void aNewCauseAdoptsAlarmsWhoseCauseIsNoLongerActive() {
        var stale = input("a1", "SDPS-A", "RAD-N").withCorrelation(link("gone", "RAD-N", SENSOR_INPUT));
        var out = correlator.apply(open(stale), raised(failure("c1", "RAD-N", CRITICAL)));
        assertThat(out).hasSize(2);
        assertThat(out.get(1).alarm().correlation()).isEqualTo(link("c1", "RAD-N", SENSOR_INPUT));
    }

    @Test
    void aReRaisedCauseGroupsAgain() {
        var cause = alarm("c1", "RAD-N", 101, 0, "", CRITICAL, CLEARED_UNACK);
        var grouped = input("a1", "SDPS-A", "RAD-N").withCorrelation(link("c1", "RAD-N", SENSOR_INPUT));
        var open = open(cause, grouped);
        var out = correlator.apply(open, raised(alarm("c1", "RAD-N", 101, 0, "", CRITICAL, ACTIVE_UNACK)));
        assertThat(out).hasSize(1);
        assertThat(open.get(grouped.key()).correlation()).isEqualTo(link("c1", "RAD-N", SENSOR_INPUT));
    }

    @Test
    void aReRaiseIsDecidedAfresh() {
        var old = alarm("a1", "SDPS-A", 401, 1, "RAD-N", MINOR, CLEARED_UNACK)
                .withCorrelation(link("closed", "RAD-N", SENSOR_INPUT));
        var reRaised = input("a1", "SDPS-A", "RAD-N").withCorrelation(old.correlation());
        assertThat(raisedAlarm(open(old), reRaised).correlation()).isNull();
        var withCause = open(old, failure("c2", "RAD-N", CRITICAL));
        assertThat(raisedAlarm(withCause, reRaised).correlation()).isEqualTo(link("c2", "RAD-N", SENSOR_INPUT));
    }

    @Test
    void otherChangesCarryTheCurrentCorrelation() {
        var correlated = alarm("a1", "RAD-N", 201, 0, "", MAJOR, ACTIVE_UNACK)
                .withCorrelation(link("c1", "RAD-N", SAME_UNIT));
        var open = open(failure("c1", "RAD-N", CRITICAL), correlated);
        for (AlarmEventType type : List.of(ACKNOWLEDGED, CLEARED, UPDATED)) {
            var plain = alarm("a1", "RAD-N", 201, 0, "", MAJOR, ACTIVE_ACK);
            var out = correlator.apply(open, new AlarmChange(type, plain, Via.OPERATOR, "u"));
            assertThat(out).hasSize(1);
            assertThat(out.get(0).alarm().correlation()).isEqualTo(link("c1", "RAD-N", SAME_UNIT));
            assertThat(out.get(0).actor()).isEqualTo("u");
        }
    }

    @Test
    void escalationAboveTheCauseDetaches() {
        var correlated = alarm("a1", "RAD-N", 201, 0, "", MAJOR, ACTIVE_UNACK)
                .withCorrelation(link("c1", "RAD-N", SAME_UNIT));
        var open = open(failure("c1", "RAD-N", MAJOR), correlated);
        var out = correlator.apply(open, updated(alarm("a1", "RAD-N", 201, 0, "", CRITICAL, ACTIVE_UNACK)));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).alarm().correlation()).isNull();
    }

    @Test
    void aCauseThatDeEscalatesDetachesTheMoreSevere() {
        var a201 = alarm("a1", "RAD-N", 201, 0, "", MAJOR, ACTIVE_UNACK)
                .withCorrelation(link("c1", "RAD-N", SAME_UNIT));
        var a401 = input("a2", "SDPS-A", "RAD-N").withCorrelation(link("c1", "RAD-N", SENSOR_INPUT));
        var open = open(failure("c1", "RAD-N", CRITICAL), a201, a401);
        var out = correlator.apply(open, updated(failure("c1", "RAD-N", MINOR)));
        assertThat(out).extracting(c -> c.type(), c -> c.via(), c -> c.alarm().id(),
                        c -> c.alarm().correlation())
                .containsExactly(tuple(UPDATED, Via.TRAP, "c1", null),
                        tuple(UPDATED, Via.CORRELATION, "a1", null));
    }

    @Test
    void fromInventoryTakesPairsAndRadars() {
        var topology = CorrelationTopology.from(Inventory.from(TestInventories.v01(20000, 20100)));
        assertThat(topology.partners()).hasSize(4);
        assertThat(topology.sensors()).containsExactlyInAnyOrder("RAD-N", "RAD-E", "RAD-S");
    }
}
