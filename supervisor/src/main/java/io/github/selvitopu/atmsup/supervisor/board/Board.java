package io.github.selvitopu.atmsup.supervisor.board;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmKey;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** The durable operational state: the fold of the event log. Immutable; maps keep their insertion order. */
public record Board(Map<String, EquipmentState> equipment, Map<ServiceId, ServiceState> services,
                    Map<AlarmKey, Alarm> openAlarms, long lastEventId) {

    public Board {
        equipment = Collections.unmodifiableMap(new LinkedHashMap<>(equipment));
        services = Collections.unmodifiableMap(new LinkedHashMap<>(services));
        openAlarms = Collections.unmodifiableMap(new LinkedHashMap<>(openAlarms));
    }

    /** Every device UNKNOWN/NOT_YET_POLLED, not in maintenance, no services; the role comes from the inventory. */
    public static Board initial(Inventory inv, Collection<Alarm> openAlarms) {
        Map<String, EquipmentState> eq = new LinkedHashMap<>();
        for (Device d : inv.devices()) {
            Role role = inv.partner(d.id()).isEmpty() ? Role.STANDALONE
                    : inv.startsActive(d.id()) ? Role.ACTIVE : Role.STANDBY;
            eq.put(d.id(), new EquipmentState(d.id(), Health.UNKNOWN, UnknownReason.NOT_YET_POLLED, false, role,
                    null, new Contact(false, null, null)));
        }
        Map<AlarmKey, Alarm> alarms = new LinkedHashMap<>();
        openAlarms.forEach(a -> alarms.put(a.key(), a));
        return new Board(eq, Map.of(), alarms, 0);
    }
}
