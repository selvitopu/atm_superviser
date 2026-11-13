package io.github.selvitopu.atmsup.supervisor.alarms;

import static io.github.selvitopu.atmsup.mib.Severity.CRITICAL;
import static io.github.selvitopu.atmsup.mib.Severity.MAJOR;
import static io.github.selvitopu.atmsup.mib.Severity.MINOR;
import static io.github.selvitopu.atmsup.supervisor.alarms.AlarmEventType.ACKNOWLEDGED;
import static io.github.selvitopu.atmsup.supervisor.alarms.AlarmEventType.CLEARED;
import static io.github.selvitopu.atmsup.supervisor.alarms.AlarmEventType.CLOSED;
import static io.github.selvitopu.atmsup.supervisor.alarms.AlarmEventType.RAISED;
import static io.github.selvitopu.atmsup.supervisor.alarms.AlarmEventType.UPDATED;
import static io.github.selvitopu.atmsup.supervisor.alarms.AlarmSource.DEVICE;
import static io.github.selvitopu.atmsup.supervisor.alarms.AlarmSource.SUPERVISOR;
import static io.github.selvitopu.atmsup.supervisor.alarms.AlarmState.ACTIVE_ACK;
import static io.github.selvitopu.atmsup.supervisor.alarms.AlarmState.ACTIVE_UNACK;
import static io.github.selvitopu.atmsup.supervisor.alarms.AlarmState.CLEARED_UNACK;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.supervisor.snmp.Notification;
import io.github.selvitopu.atmsup.supervisor.snmp.Notification.AlarmClearedNotification;
import io.github.selvitopu.atmsup.supervisor.snmp.Notification.AlarmRaisedNotification;
import io.github.selvitopu.atmsup.supervisor.snmp.Notification.StatusChangeNotification;
import io.github.selvitopu.atmsup.supervisor.snmp.Notification.SwitchoverNotification;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AlarmEngineTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-01-01T10:05:00Z");

    private final AtomicInteger seq = new AtomicInteger();
    private final AlarmEngine engine = new AlarmEngine(() -> "a" + seq.incrementAndGet());

    private static Notification raised(String eq, int code, int instance, Severity sev) {
        return new AlarmRaisedNotification(eq, T0, code, instance, "inst", sev, "text " + code);
    }

    private static Notification cleared(String eq, int code, int instance) {
        return new AlarmClearedNotification(eq, T0, code, instance);
    }

    private static Alarm alarm(String id, String eq, int code, int instance, Severity sev, AlarmState state) {
        return new Alarm(id, new AlarmKey(eq, code, instance), "inst", sev, "text " + code, DEVICE, state,
                false, T0, null, null, null, state == CLEARED_UNACK ? T0 : null);
    }

    private static Alarm alarm401Minor() {
        return alarm("a1", "SDPS-A", 401, 1, MINOR, ACTIVE_UNACK);
    }

    private static Alarm acked(Alarm a) {
        return new Alarm(a.id(), a.key(), a.instanceName(), a.severity(), a.text(), a.source(), ACTIVE_ACK,
                a.suppressed(), a.raisedAt(), T0, "demo-user", "seen", null);
    }

    private static Map<AlarmKey, Alarm> open(Alarm... alarms) {
        Map<AlarmKey, Alarm> m = new LinkedHashMap<>();
        for (Alarm a : alarms) {
            m.put(a.key(), a);
        }
        return m;
    }

    @Test
    void raiseCreatesActiveUnack() {
        var c = engine.onNotification(Map.of(), raised("SDPS-A", 401, 1, MINOR), false, T0).get(0);
        assertThat(c.type()).isEqualTo(RAISED);
        assertThat(c.via()).isEqualTo(Via.TRAP);
        assertThat(c.alarm()).extracting(Alarm::id, Alarm::state, Alarm::source)
                .containsExactly("a1", ACTIVE_UNACK, DEVICE);
        assertThat(c.alarm().raisedAt()).isEqualTo(T0);
    }

    @Test
    void duplicateRaiseIsIgnored() {
        assertThat(engine.onNotification(open(alarm401Minor()), raised("SDPS-A", 401, 1, MINOR), false, T1)).isEmpty();
    }

    @Test
    void clearWithoutOpenAlarmIsIgnored() {
        assertThat(engine.onNotification(Map.of(), cleared("SDPS-A", 401, 1), false, T0)).isEmpty();
    }

    @Test
    void sameCodeDifferentInstanceIsSeparateAlarm() {
        assertThat(engine.onNotification(open(alarm401Minor()), raised("SDPS-A", 401, 2, MINOR), false, T1))
                .extracting(AlarmChange::type).containsExactly(RAISED);
    }

    @Test
    void escalationReUnacknowledges() {
        var c = engine.onNotification(open(acked(alarm("a1", "REC", 801, 0, MINOR, ACTIVE_UNACK))),
                raised("REC", 801, 0, MAJOR), false, T1).get(0);
        assertThat(c.type()).isEqualTo(UPDATED);
        assertThat(c.alarm().state()).isEqualTo(ACTIVE_UNACK);
        assertThat(c.alarm().severity()).isEqualTo(MAJOR);
        assertThat(c.alarm().ackedBy()).isNull();
        assertThat(c.alarm().ackedAt()).isNull();
        assertThat(c.alarm().ackComment()).isNull();
    }

    @Test
    void deEscalationKeepsAck() {
        var c = engine.onNotification(open(acked(alarm("a1", "REC", 801, 0, MAJOR, ACTIVE_UNACK))),
                raised("REC", 801, 0, MINOR), false, T1).get(0);
        assertThat(c.type()).isEqualTo(UPDATED);
        assertThat(c.alarm().state()).isEqualTo(ACTIVE_ACK);
        assertThat(c.alarm().severity()).isEqualTo(MINOR);
        assertThat(c.alarm().ackedBy()).isEqualTo("demo-user");
    }

    @Test
    void textChangeWithSameSeverityUpdates() {
        var n = new AlarmRaisedNotification("SDPS-A", T1, 401, 1, "inst", MINOR, "other text");
        var c = engine.onNotification(open(alarm401Minor()), n, false, T1).get(0);
        assertThat(c.type()).isEqualTo(UPDATED);
        assertThat(c.alarm().text()).isEqualTo("other text");
    }

    @Test
    void clearOfUnackedBecomesClearedUnack() {
        var c = engine.onNotification(open(alarm401Minor()), cleared("SDPS-A", 401, 1), false, T1).get(0);
        assertThat(c.type()).isEqualTo(CLEARED);
        assertThat(c.alarm().state()).isEqualTo(CLEARED_UNACK);
        assertThat(c.alarm().clearedAt()).isEqualTo(T1);
    }

    @Test
    void clearOfAckedClosesInOneEvent() {
        assertThat(engine.onNotification(open(acked(alarm401Minor())), cleared("SDPS-A", 401, 1), false, T1))
                .extracting(AlarmChange::type).containsExactly(CLOSED);
    }

    @Test
    void clearOfAlreadyClearedIsIgnored() {
        var cu = alarm("a1", "SDPS-A", 401, 1, MINOR, CLEARED_UNACK);
        assertThat(engine.onNotification(open(cu), cleared("SDPS-A", 401, 1), false, T1)).isEmpty();
    }

    @Test
    void reRaiseOfClearedUnackKeepsId() {
        var cu = alarm("a1", "SDPS-A", 401, 1, MINOR, CLEARED_UNACK);
        var c = engine.onNotification(open(cu), raised("SDPS-A", 401, 1, MINOR), false, T1).get(0);
        assertThat(c.type()).isEqualTo(RAISED);
        assertThat(c.alarm().id()).isEqualTo("a1");
        assertThat(c.alarm().state()).isEqualTo(ACTIVE_UNACK);
        assertThat(c.alarm().clearedAt()).isNull();
    }

    @Test
    void statusChangeAndSwitchoverAreIgnored() {
        assertThat(engine.onNotification(open(alarm401Minor()), new StatusChangeNotification("SDPS-A", T0), false, T0))
                .isEmpty();
        assertThat(engine.onNotification(open(alarm401Minor()), new SwitchoverNotification("SDPS-A", T0), false, T0))
                .isEmpty();
    }

    @Test
    void raisedDuringMaintenanceIsSuppressed() {
        assertThat(engine.onNotification(Map.of(), raised("RAD-N", 101, 0, CRITICAL), true, T0).get(0)
                .alarm().suppressed()).isTrue();
    }

    @Test
    void inputMapIsNeverMutated() {
        var in = open(alarm401Minor());
        engine.onNotification(in, cleared("SDPS-A", 401, 1), false, T1);
        assertThat(in.get(new AlarmKey("SDPS-A", 401, 1)).state()).isEqualTo(ACTIVE_UNACK);
    }

    @Test
    void lostContactRaises901OnceAndRestoreClears() {
        var first = engine.onContact(Map.of(), "RAD-N", true, false, T0);
        assertThat(first).extracting(AlarmChange::type).containsExactly(RAISED);
        var a = first.get(0).alarm();
        assertThat(a.key()).isEqualTo(new AlarmKey("RAD-N", 901, 0));
        assertThat(a.source()).isEqualTo(SUPERVISOR);
        assertThat(a.severity()).isEqualTo(MAJOR);
        assertThat(a.text()).isEqualTo("Lost contact");
        assertThat(first.get(0).via()).isEqualTo(Via.SUPERVISOR);

        assertThat(engine.onContact(open(a), "RAD-N", true, false, T1)).isEmpty();
        var restored = engine.onContact(open(a), "RAD-N", false, false, T1);
        assertThat(restored).extracting(AlarmChange::type).containsExactly(CLEARED);
        assertThat(restored.get(0).via()).isEqualTo(Via.SUPERVISOR);
        assertThat(engine.onContact(Map.of(), "RAD-N", false, false, T1)).isEmpty();
    }

    @Test
    void restoreOfAckedLostContactCloses() {
        var a = acked(engine.onContact(Map.of(), "RAD-N", true, false, T0).get(0).alarm());
        assertThat(engine.onContact(open(a), "RAD-N", false, false, T1))
                .extracting(AlarmChange::type).containsExactly(CLOSED);
    }

    @Test
    void serviceFailedRaises904AndUnknownKeepsIt() {
        var before = new ServiceAlarmInput("SURV", "Surveillance", false, false);
        var failed = new ServiceAlarmInput("SURV", "Surveillance", true, false);
        var raised = engine.onServiceTransition(Map.of(), before, failed, T0);
        assertThat(raised).extracting(AlarmChange::type).containsExactly(RAISED);
        var a = raised.get(0).alarm();
        assertThat(a.key()).isEqualTo(new AlarmKey("SURV", 904, 0));
        assertThat(a.severity()).isEqualTo(CRITICAL);
        assertThat(a.text()).isEqualTo("Service failed: Surveillance");
        assertThat(a.source()).isEqualTo(SUPERVISOR);

        var unknown = new ServiceAlarmInput("SURV", "Surveillance", null, false);
        assertThat(engine.onServiceTransition(open(a), failed, unknown, T1)).isEmpty();
        var ok = new ServiceAlarmInput("SURV", "Surveillance", false, false);
        assertThat(engine.onServiceTransition(open(a), unknown, ok, T1))
                .extracting(AlarmChange::type).containsExactly(CLEARED);
    }

    @Test
    void serviceFailedIsNotRaisedTwiceAndNullBeforeWorks() {
        var failed = new ServiceAlarmInput("SURV", "Surveillance", true, null);
        var a = engine.onServiceTransition(Map.of(), null, failed, T0).get(0).alarm();
        assertThat(engine.onServiceTransition(open(a), failed, failed, T1)).isEmpty();
    }

    @Test
    void redundancyLostRaises903() {
        var after = new ServiceAlarmInput("FD", "Flight Data", false, true);
        var c = engine.onServiceTransition(Map.of(), null, after, T0).get(0);
        assertThat(c.alarm().key()).isEqualTo(new AlarmKey("FD", 903, 0));
        assertThat(c.alarm().severity()).isEqualTo(MAJOR);
        assertThat(c.alarm().text()).isEqualTo("Service redundancy lost: Flight Data");
    }

    @Test
    void ackHandlesAllStates() {
        var unack = alarm("a1", "E1", 401, 1, MINOR, ACTIVE_UNACK);
        var cu = alarm("a2", "E2", 401, 1, MINOR, CLEARED_UNACK);
        var ak = acked(alarm("a3", "E3", 401, 1, MINOR, ACTIVE_UNACK));
        AckResult r = engine.acknowledge(open(unack, cu, ak), List.of("a1", "a2", "a3", "zz"), "demo-user", "seen", T1);
        assertThat(r.changes()).extracting(AlarmChange::type).containsExactly(ACKNOWLEDGED, CLOSED);
        assertThat(r.changes()).extracting(AlarmChange::via).containsOnly(Via.OPERATOR);
        assertThat(r.changes()).extracting(AlarmChange::actor).containsOnly("demo-user");
        assertThat(r.changes().get(0).alarm())
                .extracting(Alarm::state, Alarm::ackedBy, Alarm::ackComment, Alarm::ackedAt)
                .containsExactly(ACTIVE_ACK, "demo-user", "seen", T1);
        assertThat(r.changes().get(1).alarm().state()).isEqualTo(AlarmState.CLOSED);
        assertThat(r.acknowledged()).extracting(Alarm::id).containsExactly("a1", "a2", "a3");
        assertThat(r.notFound()).containsExactly("zz");
    }

    @Test
    void maintenanceToggleUpdatesSuppression() {
        var a = alarm("a1", "RAD-N", 101, 0, MAJOR, ACTIVE_UNACK);
        var b = alarm("a2", "RAD-N", 102, 0, MAJOR, ACTIVE_UNACK);
        var other = alarm("a3", "RAD-S", 101, 0, MAJOR, ACTIVE_UNACK);
        var changes = engine.onMaintenance(open(a, b, other), "RAD-N", true);
        assertThat(changes).extracting(AlarmChange::type).containsExactly(UPDATED, UPDATED);
        assertThat(changes).allSatisfy(c -> assertThat(c.alarm().suppressed()).isTrue());
        assertThat(engine.onMaintenance(open(a), "RAD-N", false)).isEmpty();
    }

    @Test
    void contactLostRestoredLostAgainReRaises901() {
        var raised = engine.onContact(Map.of(), "RAD-N", true, false, T0).get(0).alarm();
        var cleared = engine.onContact(open(raised), "RAD-N", false, false, T0).get(0).alarm();
        assertThat(cleared.state()).isEqualTo(CLEARED_UNACK);
        var again = engine.onContact(open(cleared), "RAD-N", true, false, T1);
        assertThat(again).extracting(AlarmChange::type).containsExactly(RAISED);
        assertThat(again.get(0).alarm().id()).isEqualTo(raised.id());
        assertThat(again.get(0).alarm().state()).isEqualTo(ACTIVE_UNACK);
        assertThat(again.get(0).alarm().clearedAt()).isNull();
    }

    @Test
    void serviceFailClearFailReRaises904() {
        var failed = new ServiceAlarmInput("SURV", "Surveillance", true, null);
        var ok = new ServiceAlarmInput("SURV", "Surveillance", false, null);
        var raised = engine.onServiceTransition(Map.of(), null, failed, T0).get(0).alarm();
        var cleared = engine.onServiceTransition(open(raised), failed, ok, T0).get(0).alarm();
        var again = engine.onServiceTransition(open(cleared), ok, failed, T1);
        assertThat(again).extracting(AlarmChange::type).containsExactly(RAISED);
        assertThat(again.get(0).alarm().id()).isEqualTo(raised.id());
        assertThat(again.get(0).alarm().state()).isEqualTo(ACTIVE_UNACK);
        assertThat(again.get(0).alarm().clearedAt()).isNull();
    }

    @Test
    void redundancyLostClearLostReRaises903() {
        var lost = new ServiceAlarmInput("FD", "Flight Data", false, true);
        var ok = new ServiceAlarmInput("FD", "Flight Data", false, false);
        var raised = engine.onServiceTransition(Map.of(), null, lost, T0).get(0).alarm();
        var cleared = engine.onServiceTransition(open(raised), lost, ok, T0).get(0).alarm();
        var again = engine.onServiceTransition(open(cleared), ok, lost, T1);
        assertThat(again).extracting(AlarmChange::type).containsExactly(RAISED);
        assertThat(again.get(0).alarm().id()).isEqualTo(raised.id());
        assertThat(again.get(0).alarm().state()).isEqualTo(ACTIVE_UNACK);
    }

    @Test
    void openKeyFormat() {
        assertThat(new AlarmKey("SDPS-A", 401, 1).openKey()).isEqualTo("SDPS-A|401|1");
    }
}
