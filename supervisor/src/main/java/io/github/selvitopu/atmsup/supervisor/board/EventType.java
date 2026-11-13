package io.github.selvitopu.atmsup.supervisor.board;

import io.github.selvitopu.atmsup.supervisor.alarms.AlarmEventType;

public enum EventType {
    EQUIPMENT_STATE_CHANGED(EntityKind.EQUIPMENT),
    CONTACT_LOST(EntityKind.EQUIPMENT),
    CONTACT_RESTORED(EntityKind.EQUIPMENT),
    SERVICE_STATE_CHANGED(EntityKind.SERVICE),
    ALARM_RAISED(EntityKind.ALARM),
    ALARM_UPDATED(EntityKind.ALARM),
    ALARM_ACKNOWLEDGED(EntityKind.ALARM),
    ALARM_CLEARED(EntityKind.ALARM),
    ALARM_CLOSED(EntityKind.ALARM),
    SUPERVISOR_STARTED(EntityKind.SUPERVISOR),
    SUPERVISOR_HEARTBEAT(EntityKind.SUPERVISOR),
    SUPERVISOR_STOPPING(EntityKind.SUPERVISOR),
    TELEMETRY_SAMPLED(EntityKind.TELEMETRY);

    private final EntityKind kind;

    EventType(EntityKind kind) {
        this.kind = kind;
    }

    public EntityKind kind() {
        return kind;
    }

    public static EventType ofAlarm(AlarmEventType t) {
        return switch (t) {
            case RAISED -> ALARM_RAISED;
            case UPDATED -> ALARM_UPDATED;
            case ACKNOWLEDGED -> ALARM_ACKNOWLEDGED;
            case CLEARED -> ALARM_CLEARED;
            case CLOSED -> ALARM_CLOSED;
        };
    }
}
