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
import static org.assertj.core.api.Assertions.tuple;

import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.supervisor.snmp.DeviceAlarm;
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

    // ---- reconciliation against the device's alarm table (spec §8.2)

    /** When the device says the alarm was raised: before the poll, and different from the supervisor's clock. */
    private static final Instant DEVICE_RAISED_AT = Instant.parse("2026-01-01T09:59:18Z");

    /** A table row that matches {@link #alarm} in instance name and text. */
    private static DeviceAlarm row(int code, int instance, Severity sev) {
        return row(code, instance, sev, "text " + code);
    }

    private static DeviceAlarm row(int code, int instance, Severity sev, String text) {
        return new DeviceAlarm(code, instance, "inst", sev, text, DEVICE_RAISED_AT);
    }

    @Test
    void raisesATableAlarmMissingFromTheBoard() {
        var other = alarm("s1", "RAD-S", 101, 0, CRITICAL, ACTIVE_UNACK);

        var changes = engine.reconcile(open(other), "RAD-N", List.of(row(101, 0, CRITICAL, "Transmitter fault")),
                false, T1);

        assertThat(changes).extracting(AlarmChange::type, AlarmChange::via).containsExactly(tuple(RAISED, Via.RESYNC));
        assertThat(changes.get(0).alarm())
                .extracting(Alarm::id, Alarm::key, Alarm::instanceName, Alarm::severity, Alarm::text, Alarm::source,
                        Alarm::state, Alarm::suppressed, Alarm::raisedAt, Alarm::clearedAt)
                .containsExactly("a1", new AlarmKey("RAD-N", 101, 0), "inst", CRITICAL, "Transmitter fault", DEVICE,
                        ACTIVE_UNACK, false, DEVICE_RAISED_AT, null);
    }

    @Test
    void clearsAnActiveAlarmMissingFromTheTable() {
        var unacked = alarm("s1", "RAD-N", 201, 0, MINOR, ACTIVE_UNACK);
        var ackedOne = acked(alarm("s2", "RAD-N", 202, 0, MINOR, ACTIVE_UNACK));
        var alreadyCleared = alarm("s3", "RAD-N", 204, 0, MINOR, CLEARED_UNACK);
        var otherDevice = alarm("s4", "RAD-S", 201, 0, MINOR, ACTIVE_UNACK);

        var changes = engine.reconcile(open(unacked, ackedOne, alreadyCleared, otherDevice), "RAD-N", List.of(),
                false, T1);

        // the same lifecycle as a trap clear: an unacknowledged alarm waits for its ack, an acknowledged one closes
        assertThat(changes).extracting(c -> c.alarm().id(), AlarmChange::type, c -> c.alarm().state(),
                        c -> c.alarm().clearedAt(), AlarmChange::via)
                .containsExactly(tuple("s1", CLEARED, CLEARED_UNACK, T1, Via.RESYNC),
                        tuple("s2", CLOSED, AlarmState.CLOSED, T1, Via.RESYNC));
    }

    @Test
    void escalationGoesBackToUnacknowledged() {
        var a = acked(alarm("s1", "RAD-N", 201, 0, MINOR, ACTIVE_UNACK));

        var changes = engine.reconcile(open(a), "RAD-N", List.of(row(201, 0, MAJOR)), false, T1);

        assertThat(changes).extracting(AlarmChange::type, AlarmChange::via).containsExactly(tuple(UPDATED, Via.RESYNC));
        assertThat(changes.get(0).alarm())
                .extracting(Alarm::id, Alarm::severity, Alarm::state, Alarm::ackedAt, Alarm::ackedBy, Alarm::ackComment)
                .containsExactly("s1", MAJOR, ACTIVE_UNACK, null, null, null);
    }

    @Test
    void deEscalationKeepsTheAcknowledgement() {
        var a = acked(alarm("s1", "RAD-N", 201, 0, MAJOR, ACTIVE_UNACK));

        var changes = engine.reconcile(open(a), "RAD-N", List.of(row(201, 0, MINOR)), false, T1);

        assertThat(changes).extracting(AlarmChange::type, AlarmChange::via).containsExactly(tuple(UPDATED, Via.RESYNC));
        assertThat(changes.get(0).alarm())
                .extracting(Alarm::id, Alarm::severity, Alarm::state, Alarm::ackedAt, Alarm::ackedBy, Alarm::ackComment)
                .containsExactly("s1", MINOR, ACTIVE_ACK, T0, "demo-user", "seen");
    }

    @Test
    void sameSeverityUpdatesOnlyAChangedText() {
        var a = acked(alarm("s1", "RAD-N", 201, 0, MINOR, ACTIVE_UNACK));

        var changes = engine.reconcile(open(a), "RAD-N", List.of(row(201, 0, MINOR, "Channel A failed")), false, T1);

        assertThat(changes).extracting(AlarmChange::type, AlarmChange::via).containsExactly(tuple(UPDATED, Via.RESYNC));
        var expected = new Alarm("s1", a.key(), "inst", MINOR, "Channel A failed", DEVICE, ACTIVE_ACK, false, T0, T0,
                "demo-user", "seen", null);
        assertThat(changes.get(0).alarm()).isEqualTo(expected);
    }

    @Test
    void anIdenticalTableChangesNothing() {
        var a = alarm("s1", "RAD-N", 201, 0, MINOR, ACTIVE_UNACK);
        var b = acked(alarm("s2", "RAD-N", 401, 1, MAJOR, ACTIVE_UNACK));

        assertThat(engine.reconcile(open(a, b), "RAD-N", List.of(row(201, 0, MINOR), row(401, 1, MAJOR)), false, T1))
                .isEmpty();
    }

    @Test
    void aClearedUnacknowledgedAlarmStillInTheTableIsReRaisedWithTheSameId() {
        var cu = alarm("s1", "RAD-N", 201, 0, MINOR, CLEARED_UNACK);

        var changes = engine.reconcile(open(cu), "RAD-N", List.of(row(201, 0, MAJOR, "Channel A failed")), false, T1);

        assertThat(changes).extracting(AlarmChange::type, AlarmChange::via).containsExactly(tuple(RAISED, Via.RESYNC));
        assertThat(changes.get(0).alarm())
                .extracting(Alarm::id, Alarm::severity, Alarm::text, Alarm::state, Alarm::clearedAt)
                .containsExactly("s1", MAJOR, "Channel A failed", ACTIVE_UNACK, null);
    }

    @Test
    void aReRaiseFromTheTableTakesTheDevicesRaiseTime() {
        var cu = alarm("s1", "RAD-N", 201, 0, MINOR, CLEARED_UNACK);

        var resync = engine.reconcile(open(cu), "RAD-N", List.of(row(201, 0, MINOR)), false, T1).get(0);
        var trap = engine.onNotification(open(cu), raised("RAD-N", 201, 0, MINOR), false, T1).get(0);

        // spec §8.2: a resync raise uses the device's raisedAt; a trap re-raise keeps the alarm's first raise time
        assertThat(resync.alarm()).extracting(Alarm::id, Alarm::state, Alarm::raisedAt)
                .containsExactly("s1", ACTIVE_UNACK, DEVICE_RAISED_AT);
        assertThat(trap.alarm()).extracting(Alarm::id, Alarm::state, Alarm::raisedAt)
                .containsExactly("s1", ACTIVE_UNACK, T0);
    }

    @Test
    void raisesDuringMaintenanceAreSuppressed() {
        var cu = alarm("s1", "RAD-N", 201, 0, MINOR, CLEARED_UNACK);

        var changes = engine.reconcile(open(cu), "RAD-N", List.of(row(101, 0, CRITICAL), row(201, 0, MINOR)), true,
                T1);

        assertThat(changes).extracting(c -> c.alarm().key().code(), AlarmChange::type, c -> c.alarm().suppressed(),
                        AlarmChange::via)
                .containsExactly(tuple(101, RAISED, true, Via.RESYNC), tuple(201, RAISED, true, Via.RESYNC));
    }

    @Test
    void supervisorAlarmsAreNeverReconciled() {
        var lostContact = engine.onContact(Map.of(), "RAD-N", true, false, T0).get(0).alarm();
        var failed = new ServiceAlarmInput("SURV", "Surveillance", true, true);
        var serviceAlarms = engine.onServiceTransition(Map.of(), null, failed, T0).stream()
                .map(AlarmChange::alarm).toArray(Alarm[]::new);
        var open = open(serviceAlarms);
        open.put(lostContact.key(), lostContact);

        assertThat(engine.reconcile(open, "RAD-N", List.of(), false, T1)).isEmpty();
        assertThat(engine.reconcile(open, "SURV", List.of(), false, T1)).isEmpty();
        // a table row on the key of a supervisor alarm does not take it over either
        assertThat(engine.reconcile(open, "RAD-N", List.of(row(901, 0, CRITICAL, "Device text")), false, T1))
                .isEmpty();
    }

    @Test
    void supervisorCodesInTheTableAreIgnored() {
        var table = List.of(row(901, 0, MAJOR, "Lost contact"), row(903, 0, MAJOR), row(904, 0, CRITICAL),
                row(101, 0, CRITICAL));

        var changes = engine.reconcile(Map.of(), "RAD-N", table, false, T1);

        // a DEVICE 901 would be cleared by the next probe and raised again by the next poll, every few seconds
        assertThat(changes).extracting(c -> c.alarm().key().code(), AlarmChange::type, AlarmChange::via)
                .containsExactly(tuple(101, RAISED, Via.RESYNC));
    }

    @Test
    void supervisorCodesInATrapAreIgnored() {
        var lostContact = engine.onContact(Map.of(), "RAD-N", true, false, T0).get(0).alarm();

        for (int code : List.of(901, 903, 904)) {
            assertThat(engine.onNotification(Map.of(), raised("RAD-N", code, 0, MAJOR), false, T1)).isEmpty();
            assertThat(engine.onNotification(open(lostContact), cleared("RAD-N", code, 0), false, T1)).isEmpty();
        }
    }

    @Test
    void changesAreOrderedByCodeThenInstance() {
        var a = alarm("s1", "SDPS-A", 401, 2, MINOR, ACTIVE_UNACK);
        var b = alarm("s2", "SDPS-A", 201, 0, MINOR, ACTIVE_UNACK);
        var table = List.of(row(401, 3, MINOR), row(101, 0, MINOR), row(401, 1, MINOR));

        var changes = engine.reconcile(open(a, b), "SDPS-A", table, false, T1);

        assertThat(changes).extracting(c -> c.alarm().key().code(), c -> c.alarm().key().instance(),
                        AlarmChange::type, AlarmChange::via)
                .containsExactly(tuple(101, 0, RAISED, Via.RESYNC), tuple(201, 0, CLEARED, Via.RESYNC),
                        tuple(401, 1, RAISED, Via.RESYNC), tuple(401, 2, CLEARED, Via.RESYNC),
                        tuple(401, 3, RAISED, Via.RESYNC));
    }

    @Test
    void everyCopyKeepsTheCorrelation() {
        var link = new Correlation("c1", "RAD-N", 101, CorrelationRule.SAME_UNIT);
        var a = alarm("a1", "RAD-N", 201, 0, MAJOR, ACTIVE_UNACK).withCorrelation(link);
        var open = open(a);

        var acked = engine.acknowledge(open, List.of("a1"), "u", "c", T1).changes().get(0).alarm();
        assertThat(acked.correlation()).isEqualTo(link);
        var cleared = engine.onNotification(open, cleared("RAD-N", 201, 0), false, T1).get(0).alarm();
        assertThat(cleared.correlation()).isEqualTo(link);
        var escalated = engine.onNotification(open, raised("RAD-N", 201, 0, CRITICAL), false, T1).get(0).alarm();
        assertThat(escalated.correlation()).isEqualTo(link);
        var maintained = engine.onMaintenance(open, "RAD-N", true).get(0).alarm();
        assertThat(maintained.correlation()).isEqualTo(link);

        var gone = alarm("a1", "RAD-N", 201, 0, MAJOR, CLEARED_UNACK).withCorrelation(link);
        var reRaised = engine.onNotification(open(gone), raised("RAD-N", 201, 0, MAJOR), false, T1).get(0);
        assertThat(reRaised.type()).isEqualTo(RAISED);
        assertThat(reRaised.alarm().correlation()).isEqualTo(link);
    }
}
