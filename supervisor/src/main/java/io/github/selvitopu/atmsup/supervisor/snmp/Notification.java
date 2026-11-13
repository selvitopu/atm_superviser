package io.github.selvitopu.atmsup.supervisor.snmp;

import io.github.selvitopu.atmsup.mib.Severity;
import java.time.Instant;

/** A notification received from a device, mapped from a v3 TRAP or INFORM. */
public sealed interface Notification {

    String equipmentId();

    Instant receivedAt();

    record AlarmRaisedNotification(String equipmentId, Instant receivedAt, int code, int instance,
                                   String instanceName, Severity severity, String text) implements Notification {
    }

    record AlarmClearedNotification(String equipmentId, Instant receivedAt, int code, int instance)
            implements Notification {
    }

    /** The new status and role are not carried on: the follow-up poll reads the authoritative state. */
    record StatusChangeNotification(String equipmentId, Instant receivedAt) implements Notification {
    }

    record SwitchoverNotification(String equipmentId, Instant receivedAt) implements Notification {
    }
}
