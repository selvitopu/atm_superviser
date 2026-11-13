package io.github.selvitopu.atmsup.simulator.api;

import io.github.selvitopu.atmsup.inventory.DeviceType;
import io.github.selvitopu.atmsup.mib.EquipmentStatus;
import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.simulator.model.ActiveAlarm;
import io.github.selvitopu.atmsup.simulator.model.DeviceModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the console shows of one simulated device. Each fault renders as {@code NAME:target}, or just the name when it
 * has no target, sorted. {@code values} are the type's measurements plus {@code lastCommandResult} (an enum name).
 */
public record DeviceStateDto(String id, DeviceType type, EquipmentStatus status, Role role, boolean maintenance,
                             List<String> faults, boolean responding, boolean dropTraps, List<ActiveAlarm> alarms,
                             Map<String, Object> values) {

    /** A snapshot of {@code model}; the caller serialises access to the model. */
    public static DeviceStateDto of(DeviceModel model) {
        List<String> faults = new ArrayList<>();
        model.faults().forEach((fault, target) -> faults.add(target == null ? fault.name() : fault.name() + ":" + target));
        faults.sort(null);
        Map<String, Object> values = new LinkedHashMap<>(model.values());
        values.put("lastCommandResult", model.commandResult().name());
        return new DeviceStateDto(model.id(), model.device().type(), model.status(), model.role(),
                model.maintenance(), List.copyOf(faults), !model.unreachable(), model.dropTraps(), model.alarms(),
                Collections.unmodifiableMap(values));
    }
}
