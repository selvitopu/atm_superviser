package io.github.selvitopu.atmsup.supervisor.snmp;

import static io.github.selvitopu.atmsup.mib.AtmTsOids.ALARM_INSTANCE_NAME;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.ALARM_SEVERITY;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.ALARM_TEXT;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_ID;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.NOTIF_ALARM_CLEARED;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.NOTIF_ALARM_CODE;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.NOTIF_ALARM_INSTANCE;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.NOTIF_ALARM_RAISED;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.NOTIF_EQ_STATUS_CHANGE;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.NOTIF_SWITCHOVER;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.scalar;
import static io.github.selvitopu.atmsup.mib.Severity.MINOR;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.simulator.fixture.TestInventories;
import io.github.selvitopu.atmsup.supervisor.snmp.Notification.AlarmClearedNotification;
import io.github.selvitopu.atmsup.supervisor.snmp.Notification.AlarmRaisedNotification;
import io.github.selvitopu.atmsup.supervisor.snmp.Notification.StatusChangeNotification;
import io.github.selvitopu.atmsup.supervisor.snmp.Notification.SwitchoverNotification;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.VariableBinding;

class NotificationMapperTest {

    private static final Instant T0 = Instant.parse("2026-11-13T10:00:00Z");

    private final NotificationMapper mapper = new NotificationMapper(Inventory.from(TestInventories.v01(20000, 20010)));

    @Test
    void mapsAlarmRaised() {
        assertThat(mapper.map(raised("SDPS-A", 401, 1, "RAD-N", 5, "Sensor input lost"), T0))
                .contains(new AlarmRaisedNotification("SDPS-A", T0, 401, 1, "RAD-N", MINOR, "Sensor input lost"));
    }

    @Test
    void instanceNameDefaultsToEmpty() {
        PDU pdu = raised("RAD-N", 101, 0, "x", 3, "Equipment failed");
        pdu.remove(pdu.size() - 3); // ALARM_INSTANCE_NAME
        assertThat(pdu.getVariable(row(ALARM_INSTANCE_NAME, 101, 0))).isNull();
        assertThat(mapper.map(pdu, T0)).hasValueSatisfying(n ->
                assertThat(((AlarmRaisedNotification) n).instanceName()).isEmpty());
    }

    @Test
    void mapsAlarmCleared() {
        PDU pdu = notification(NOTIF_ALARM_CLEARED, "RAD-N");
        pdu.add(new VariableBinding(scalar(NOTIF_ALARM_CODE), new Integer32(101)));
        pdu.add(new VariableBinding(scalar(NOTIF_ALARM_INSTANCE), new Integer32(0)));
        assertThat(mapper.map(pdu, T0)).contains(new AlarmClearedNotification("RAD-N", T0, 101, 0));
    }

    @Test
    void mapsStatusChangeAndSwitchover() {
        assertThat(mapper.map(notification(NOTIF_EQ_STATUS_CHANGE, "RAD-N"), T0))
                .contains(new StatusChangeNotification("RAD-N", T0));
        assertThat(mapper.map(notification(NOTIF_SWITCHOVER, "SDPS-A"), T0))
                .contains(new SwitchoverNotification("SDPS-A", T0));
    }

    @Test
    void unknownEquipmentIsDropped() {
        try (CapturedLogs logs = new CapturedLogs(NotificationMapper.class)) {
            assertThat(mapper.map(raised("X-9", 101, 0, "", 3, "x"), T0)).isEmpty();
            assertThat(mapper.rejected()).isEqualTo(1);
            assertThat(logs.warnings()).singleElement().asString().contains("unknown equipment 'X-9'");
        }
    }

    @Test
    void missingEqIdIsDropped() {
        PDU pdu = new ScopedPDU();
        pdu.add(new VariableBinding(SnmpConstants.snmpTrapOID, NOTIF_EQ_STATUS_CHANGE));
        try (CapturedLogs logs = new CapturedLogs(NotificationMapper.class)) {
            assertThat(mapper.map(pdu, T0)).isEmpty();
            assertThat(mapper.rejected()).isEqualTo(1);
            assertThat(logs.warnings()).singleElement().asString().contains("EQ_ID");
        }
    }

    @Test
    void missingSeverityIsDropped() {
        PDU pdu = raised("RAD-N", 101, 0, "", 3, "x");
        pdu.remove(pdu.size() - 2); // ALARM_SEVERITY
        assertThat(pdu.getVariable(row(ALARM_SEVERITY, 101, 0))).isNull();
        try (CapturedLogs logs = new CapturedLogs(NotificationMapper.class)) {
            assertThat(mapper.map(pdu, T0)).isEmpty();
            assertThat(mapper.rejected()).isEqualTo(1);
            assertThat(logs.warnings()).singleElement().asString().contains("severity");
        }
    }

    @Test
    void unknownSeverityCodeIsDropped() {
        try (CapturedLogs logs = new CapturedLogs(NotificationMapper.class)) {
            assertThat(mapper.map(raised("RAD-N", 101, 0, "", 99, "x"), T0)).isEmpty();
            assertThat(mapper.rejected()).isEqualTo(1);
            assertThat(logs.warnings()).hasSize(1);
        }
    }

    @Test
    void coldStartIsIgnoredNotRejected() {
        PDU pdu = new ScopedPDU();
        pdu.add(new VariableBinding(SnmpConstants.snmpTrapOID, SnmpConstants.coldStart));
        try (CapturedLogs logs = new CapturedLogs(NotificationMapper.class)) {
            assertThat(mapper.map(pdu, T0)).isEmpty();
            assertThat(mapper.rejected()).isZero();
            assertThat(logs.warnings()).isEmpty();
        }
    }

    private static OID row(OID column, int code, int instance) {
        return new OID(column).append(code).append(instance);
    }

    private static PDU notification(OID trapOid, String eqId) {
        PDU pdu = new ScopedPDU();
        pdu.setType(PDU.TRAP);
        pdu.add(new VariableBinding(SnmpConstants.sysUpTime, new TimeTicks(100)));
        pdu.add(new VariableBinding(SnmpConstants.snmpTrapOID, trapOid));
        pdu.add(new VariableBinding(scalar(EQ_ID), new OctetString(eqId)));
        return pdu;
    }

    /** Varbind order: ..., NAME, SEVERITY, TEXT (the tests above remove by position from the end). */
    private static PDU raised(String eqId, int code, int instance, String name, int severity, String text) {
        PDU pdu = notification(NOTIF_ALARM_RAISED, eqId);
        pdu.add(new VariableBinding(scalar(NOTIF_ALARM_CODE), new Integer32(code)));
        pdu.add(new VariableBinding(scalar(NOTIF_ALARM_INSTANCE), new Integer32(instance)));
        pdu.add(new VariableBinding(row(ALARM_INSTANCE_NAME, code, instance), new OctetString(name)));
        pdu.add(new VariableBinding(row(ALARM_SEVERITY, code, instance), new Integer32(severity)));
        pdu.add(new VariableBinding(row(ALARM_TEXT, code, instance), new OctetString(text)));
        return pdu;
    }
}
