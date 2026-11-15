package io.github.selvitopu.atmsup.supervisor.snmp;

import io.github.selvitopu.atmsup.mib.EquipmentStatus;
import io.github.selvitopu.atmsup.mib.Role;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** One device's full status as read at {@code observedAt}; the maps are immutable and keep insertion order;
 * {@code alarms} are the device's alarm table, ordered by (code, instance). */
public record Observation(String equipmentId, Instant observedAt, EquipmentStatus status, Role role,
                          boolean maintenance, Map<String, Object> values,
                          Map<String, List<Map<String, Object>>> tables,
                          List<DeviceAlarm> alarms) {

    public Observation {
        alarms = List.copyOf(alarms);
    }
}
