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

import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.mib.SnmpEnum;
import io.github.selvitopu.atmsup.supervisor.snmp.Notification.AlarmClearedNotification;
import io.github.selvitopu.atmsup.supervisor.snmp.Notification.AlarmRaisedNotification;
import io.github.selvitopu.atmsup.supervisor.snmp.Notification.StatusChangeNotification;
import io.github.selvitopu.atmsup.supervisor.snmp.Notification.SwitchoverNotification;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.PDU;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.Variable;

/**
 * Maps a notification PDU to a {@link Notification}. Malformed input (missing or mistyped objects, unknown equipment)
 * yields empty, a WARN with the reason and an increment of {@link #rejected()}; an unknown trap OID such as coldStart
 * is not an error and only logs at DEBUG.
 */
public final class NotificationMapper {

    private static final Logger log = LoggerFactory.getLogger(NotificationMapper.class);

    private final Inventory inventory;
    private final AtomicLong rejected = new AtomicLong();

    public NotificationMapper(Inventory inventory) {
        this.inventory = inventory;
    }

    public long rejected() {
        return rejected.get();
    }

    public Optional<Notification> map(PDU pdu, Instant receivedAt) {
        try {
            return Optional.of(mapOrThrow(pdu, receivedAt));
        } catch (Malformed e) {
            rejected.incrementAndGet();
            log.warn("Notification rejected: {}", e.getMessage());
            return Optional.empty();
        } catch (Ignored e) {
            log.debug("Notification ignored: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Notification mapOrThrow(PDU pdu, Instant at) {
        if (!(pdu.getVariable(SnmpConstants.snmpTrapOID) instanceof OID trapOid)) {
            throw new Malformed("snmpTrapOID.0 is missing or not an OID");
        }
        boolean known = trapOid.equals(NOTIF_EQ_STATUS_CHANGE) || trapOid.equals(NOTIF_ALARM_RAISED)
                || trapOid.equals(NOTIF_ALARM_CLEARED) || trapOid.equals(NOTIF_SWITCHOVER);
        if (!known) {
            throw new Ignored("unhandled trap OID " + trapOid);
        }
        if (!(pdu.getVariable(scalar(EQ_ID)) instanceof OctetString id)) {
            throw new Malformed("EQ_ID.0 is missing or not an octet string");
        }
        String equipmentId = id.toString();
        if (inventory.find(equipmentId).isEmpty()) {
            throw new Malformed("unknown equipment '" + equipmentId + "'");
        }
        if (trapOid.equals(NOTIF_EQ_STATUS_CHANGE)) {
            return new StatusChangeNotification(equipmentId, at);
        }
        if (trapOid.equals(NOTIF_SWITCHOVER)) {
            return new SwitchoverNotification(equipmentId, at);
        }
        int code = integer(pdu, scalar(NOTIF_ALARM_CODE), "alarm code");
        int instance = integer(pdu, scalar(NOTIF_ALARM_INSTANCE), "alarm instance");
        if (trapOid.equals(NOTIF_ALARM_CLEARED)) {
            return new AlarmClearedNotification(equipmentId, at, code, instance);
        }
        Severity severity;
        try {
            severity = SnmpEnum.fromCode(Severity.class, integer(pdu, row(ALARM_SEVERITY, code, instance), "severity"));
        } catch (IllegalArgumentException e) {
            throw new Malformed(e.getMessage());
        }
        String text = string(pdu, row(ALARM_TEXT, code, instance), "alarm text");
        String name = pdu.getVariable(row(ALARM_INSTANCE_NAME, code, instance)) instanceof OctetString s
                ? s.toString() : "";
        return new AlarmRaisedNotification(equipmentId, at, code, instance, name, severity, text);
    }

    private static OID row(OID column, int code, int instance) {
        return new OID(column).append(code).append(instance);
    }

    private static int integer(PDU pdu, OID oid, String what) {
        Variable v = pdu.getVariable(oid);
        if (v instanceof Integer32 i) {
            return i.getValue();
        }
        throw new Malformed(what + " " + oid + " is missing or not an integer");
    }

    private static String string(PDU pdu, OID oid, String what) {
        if (pdu.getVariable(oid) instanceof OctetString s) {
            return s.toString();
        }
        throw new Malformed(what + " " + oid + " is missing or not an octet string");
    }

    private static final class Malformed extends RuntimeException {
        Malformed(String message) {
            super(message, null, false, false);
        }
    }

    private static final class Ignored extends RuntimeException {
        Ignored(String message) {
            super(message, null, false, false);
        }
    }
}
