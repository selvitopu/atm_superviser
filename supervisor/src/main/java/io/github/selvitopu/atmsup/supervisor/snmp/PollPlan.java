package io.github.selvitopu.atmsup.supervisor.snmp;

import static io.github.selvitopu.atmsup.mib.AtmTsOids.*;

import io.github.selvitopu.atmsup.inventory.DeviceType;
import java.util.ArrayList;
import java.util.List;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.OID;

/** What a full-status poll reads: scalar instances (one GET) and table entries (one walk each). */
public record PollPlan(List<OID> scalars, List<OID> walks) {

    /** The lightweight probe object, {@code sysUpTime.0}: it proves reachability only. */
    public static final OID PROBE = SnmpConstants.sysUpTime;

    private static final List<OID> EQUIPMENT = List.of(EQ_ID, EQ_TYPE, EQ_STATUS, EQ_ROLE, EQ_STATUS_CHANGED_AT,
            EQ_SW_VERSION, EQ_MAINTENANCE_MODE, EQ_COMMAND, EQ_LAST_COMMAND_RESULT);

    public PollPlan {
        scalars = List.copyOf(scalars);
        walks = List.copyOf(walks);
    }

    /** @throws IllegalArgumentException for a type that has no poll plan in this version */
    public static PollPlan forType(DeviceType type) {
        return switch (type) {
            case RADAR -> plan(List.of(RADAR_ROTATION_PERIOD_MS, RADAR_PLOTS_PER_SCAN, RADAR_ACTIVE_CHANNEL,
                    RADAR_CHANNEL_A_STATUS, RADAR_CHANNEL_B_STATUS), List.of());
            case SDPS -> plan(List.of(SDPS_TRACK_COUNT, SDPS_LATENCY_MS), List.of(SDPS_SENSOR_ENTRY));
            case FDPS -> plan(List.of(FDPS_FLIGHT_PLAN_COUNT, FDPS_QUEUE_DEPTH, FDPS_LATENCY_MS), List.of());
            default -> throw new IllegalArgumentException("No poll plan for device type " + type);
        };
    }

    private static PollPlan plan(List<OID> specific, List<OID> walks) {
        List<OID> scalars = new ArrayList<>();
        EQUIPMENT.forEach(o -> scalars.add(scalar(o)));
        specific.forEach(o -> scalars.add(scalar(o)));
        return new PollPlan(scalars, walks);
    }
}
