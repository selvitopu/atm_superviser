package io.github.selvitopu.atmsup.simulator.model;

import io.github.selvitopu.atmsup.mib.EquipmentStatus;
import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.mib.Severity;

/** Something a device announces on its own: sent as a trap, or as an acknowledged inform for critical alarms. */
public sealed interface DeviceNotification {

    /** True when delivery must be acknowledged: only a CRITICAL alarm being raised. */
    default boolean inform() {
        return this instanceof AlarmRaised raised && raised.alarm().severity() == Severity.CRITICAL;
    }

    record StatusChange(EquipmentStatus status, Role role) implements DeviceNotification {
    }

    record AlarmRaised(ActiveAlarm alarm) implements DeviceNotification {
    }

    record AlarmCleared(int code, int instance) implements DeviceNotification {
    }

    record Switchover(Role role) implements DeviceNotification {
    }
}
