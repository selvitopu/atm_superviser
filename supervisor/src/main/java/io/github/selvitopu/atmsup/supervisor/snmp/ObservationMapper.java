package io.github.selvitopu.atmsup.supervisor.snmp;

import static io.github.selvitopu.atmsup.mib.AtmTsOids.*;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.mib.ActiveChannel;
import io.github.selvitopu.atmsup.mib.CommandResult;
import io.github.selvitopu.atmsup.mib.DateAndTimes;
import io.github.selvitopu.atmsup.mib.EquipmentStatus;
import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.mib.SensorInputStatus;
import io.github.selvitopu.atmsup.mib.SnmpEnum;
import io.github.selvitopu.atmsup.supervisor.snmp.SnmpRequestException.Kind;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.UnsignedInteger32;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;

/**
 * Turns the raw bindings of a poll into an {@link Observation}. The value keys are those of the simulator's device
 * models. Required objects (id, status, role, maintenance mode) must be present with the right syntax, otherwise the
 * poll is MALFORMED; optional objects that are missing or hold an exception value are left out.
 */
public class ObservationMapper {

    /** MIB names of the polled objects, for error messages. */
    private static final Map<OID, String> NAMES = new HashMap<>();

    static {
        NAMES.put(EQ_ID, "atmTsEqId");
        NAMES.put(EQ_TYPE, "atmTsEqType");
        NAMES.put(EQ_STATUS, "atmTsEqStatus");
        NAMES.put(EQ_ROLE, "atmTsEqRole");
        NAMES.put(EQ_STATUS_CHANGED_AT, "atmTsEqStatusChangedAt");
        NAMES.put(EQ_SW_VERSION, "atmTsEqSwVersion");
        NAMES.put(EQ_MAINTENANCE_MODE, "atmTsEqMaintenanceMode");
        NAMES.put(EQ_COMMAND, "atmTsEqCommand");
        NAMES.put(EQ_LAST_COMMAND_RESULT, "atmTsEqLastCommandResult");
        NAMES.put(RADAR_ROTATION_PERIOD_MS, "atmTsRadarRotationPeriodMs");
        NAMES.put(RADAR_PLOTS_PER_SCAN, "atmTsRadarPlotsPerScan");
        NAMES.put(RADAR_ACTIVE_CHANNEL, "atmTsRadarActiveChannel");
        NAMES.put(RADAR_CHANNEL_A_STATUS, "atmTsRadarChannelAStatus");
        NAMES.put(RADAR_CHANNEL_B_STATUS, "atmTsRadarChannelBStatus");
        NAMES.put(SDPS_TRACK_COUNT, "atmTsSdpsTrackCount");
        NAMES.put(SDPS_LATENCY_MS, "atmTsSdpsLatencyMs");
        NAMES.put(SDPS_SENSOR_NAME, "atmTsSdpsSensorName");
        NAMES.put(SDPS_SENSOR_INPUT_STATUS, "atmTsSdpsSensorInputStatus");
        NAMES.put(FDPS_FLIGHT_PLAN_COUNT, "atmTsFdpsFlightPlanCount");
        NAMES.put(FDPS_QUEUE_DEPTH, "atmTsFdpsQueueDepth");
        NAMES.put(FDPS_LATENCY_MS, "atmTsFdpsLatencyMs");
    }

    /**
     * Reads {@code sysUpTime.0} from a probe answer.
     *
     * @throws SnmpRequestException MALFORMED if it is missing, an exception value, or not TimeTicks
     */
    public ProbeObservation mapProbe(Device d, List<VariableBinding> vbs, Instant at) {
        for (VariableBinding vb : vbs) {
            if (SnmpConstants.sysUpTime.equals(vb.getOid()) && vb.getVariable() instanceof TimeTicks ticks) {
                return new ProbeObservation(d.id(), at, ticks.toLong());
            }
        }
        throw malformed("sysUpTime missing");
    }

    /**
     * Maps the scalars of a full poll (and the walked table rows, keyed by the walked entry OID).
     *
     * @throws SnmpRequestException MALFORMED when a required object is unusable, another device answered, or an enum
     *                              code is unknown
     */
    public Observation map(Device d, List<VariableBinding> scalars, Map<OID, List<VariableBinding>> walks,
                           Instant at) {
        Map<OID, Variable> objects = new HashMap<>();
        for (VariableBinding vb : scalars) {
            objects.put(vb.getOid(), vb.getVariable());
        }

        String answeredAs = requiredString(objects, EQ_ID);
        if (!d.id().equals(answeredAs)) {
            throw malformed("answered as " + answeredAs);
        }
        EquipmentStatus status = requiredEnum(objects, EQ_STATUS, EquipmentStatus.class);
        Role role = requiredEnum(objects, EQ_ROLE, Role.class);
        boolean maintenance = requiredTruthValue(objects, EQ_MAINTENANCE_MODE);

        Map<String, Object> values = new LinkedHashMap<>();
        optionalString(objects, EQ_SW_VERSION, "swVersion", values);
        optionalDateAndTime(objects, EQ_STATUS_CHANGED_AT, "statusChangedAt", values);
        optionalEnum(objects, EQ_LAST_COMMAND_RESULT, "lastCommandResult", CommandResult.class, values);
        Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
        switch (d.type()) {
            case RADAR -> {
                optionalNumber(objects, RADAR_ROTATION_PERIOD_MS, "rotationPeriodMs", values);
                optionalNumber(objects, RADAR_PLOTS_PER_SCAN, "plotsPerScan", values);
                optionalEnum(objects, RADAR_ACTIVE_CHANNEL, "activeChannel", ActiveChannel.class, values);
                optionalEnum(objects, RADAR_CHANNEL_A_STATUS, "channelAStatus", EquipmentStatus.class, values);
                optionalEnum(objects, RADAR_CHANNEL_B_STATUS, "channelBStatus", EquipmentStatus.class, values);
            }
            case SDPS -> {
                optionalNumber(objects, SDPS_TRACK_COUNT, "trackCount", values);
                optionalNumber(objects, SDPS_LATENCY_MS, "latencyMs", values);
                List<VariableBinding> rows = walks.get(SDPS_SENSOR_ENTRY);
                if (rows != null) {
                    tables.put("sensors", sensors(rows));
                }
            }
            case FDPS -> {
                optionalNumber(objects, FDPS_FLIGHT_PLAN_COUNT, "flightPlanCount", values);
                optionalNumber(objects, FDPS_QUEUE_DEPTH, "queueDepth", values);
                optionalNumber(objects, FDPS_LATENCY_MS, "latencyMs", values);
            }
            default -> throw new IllegalArgumentException("No observation mapping for device type " + d.type());
        }
        return new Observation(d.id(), at, status, role, maintenance, Collections.unmodifiableMap(values),
                Collections.unmodifiableMap(tables));
    }

    private static List<Map<String, Object>> sensors(List<VariableBinding> rows) {
        Map<Long, String> names = new TreeMap<>();
        Map<Long, SensorInputStatus> statuses = new TreeMap<>();
        for (VariableBinding vb : rows) {
            OID oid = vb.getOid();
            Variable v = vb.getVariable();
            long index = oid.last() & 0xFFFFFFFFL;
            if (oid.startsWith(SDPS_SENSOR_NAME) && v instanceof OctetString name) {
                names.put(index, name.toString());
            } else if (oid.startsWith(SDPS_SENSOR_INPUT_STATUS) && v instanceof Integer32 code) {
                statuses.put(index, fromCode(SensorInputStatus.class, code.getValue()));
            }
        }
        List<Map<String, Object>> table = new java.util.ArrayList<>();
        for (Map.Entry<Long, String> name : names.entrySet()) {
            SensorInputStatus status = statuses.get(name.getKey());
            if (status == null) {
                continue; // an incomplete row is not shown
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", name.getKey());
            row.put("name", name.getValue());
            row.put("inputStatus", status.name());
            table.add(Collections.unmodifiableMap(row));
        }
        return List.copyOf(table);
    }

    private static String requiredString(Map<OID, Variable> objects, OID object) {
        Variable v = required(objects, object);
        if (v instanceof OctetString s) {
            return s.toString();
        }
        throw wrongSyntax(object, v);
    }

    private static <E extends Enum<E> & SnmpEnum> E requiredEnum(Map<OID, Variable> objects, OID object,
                                                                 Class<E> type) {
        Variable v = required(objects, object);
        if (v instanceof Integer32 code) {
            return fromCode(type, code.getValue());
        }
        throw wrongSyntax(object, v);
    }

    /** SNMPv2-TC TruthValue: true(1), false(2). */
    private static boolean requiredTruthValue(Map<OID, Variable> objects, OID object) {
        Variable v = required(objects, object);
        if (v instanceof Integer32 code) {
            return switch (code.getValue()) {
                case 1 -> true;
                case 2 -> false;
                default -> throw malformed(NAMES.get(object) + " has value " + code.getValue());
            };
        }
        throw wrongSyntax(object, v);
    }

    private static Variable required(Map<OID, Variable> objects, OID object) {
        Variable v = objects.get(scalar(object));
        if (v == null || v.isException()) {
            throw malformed(NAMES.get(object) + " missing");
        }
        return v;
    }

    private static Variable optional(Map<OID, Variable> objects, OID object) {
        Variable v = objects.get(scalar(object));
        return v == null || v.isException() ? null : v;
    }

    private static void optionalString(Map<OID, Variable> objects, OID object, String key, Map<String, Object> out) {
        if (optional(objects, object) instanceof OctetString s) {
            out.put(key, s.toString());
        }
    }

    private static void optionalNumber(Map<OID, Variable> objects, OID object, String key, Map<String, Object> out) {
        Variable v = optional(objects, object);
        if (v instanceof Integer32 || v instanceof UnsignedInteger32) {
            out.put(key, v.toLong());
        }
    }

    private static <E extends Enum<E> & SnmpEnum> void optionalEnum(Map<OID, Variable> objects, OID object,
                                                                    String key, Class<E> type,
                                                                    Map<String, Object> out) {
        if (optional(objects, object) instanceof Integer32 code) {
            out.put(key, fromCode(type, code.getValue()).name());
        }
    }

    /** A DateAndTime that cannot be decoded is left out: the field is informational. */
    private static void optionalDateAndTime(Map<OID, Variable> objects, OID object, String key,
                                            Map<String, Object> out) {
        if (optional(objects, object) instanceof OctetString s) {
            try {
                out.put(key, DateAndTimes.decode(s).toString());
            } catch (IllegalArgumentException | DateTimeException e) {
                // malformed DateAndTime: omit
            }
        }
    }

    private static <E extends Enum<E> & SnmpEnum> E fromCode(Class<E> type, int code) {
        try {
            return SnmpEnum.fromCode(type, code);
        } catch (IllegalArgumentException e) {
            throw malformed(e.getMessage());
        }
    }

    private static SnmpRequestException wrongSyntax(OID object, Variable v) {
        return malformed(NAMES.get(object) + " has syntax " + v.getSyntaxString());
    }

    private static SnmpRequestException malformed(String detail) {
        return new SnmpRequestException(Kind.MALFORMED, detail);
    }
}
