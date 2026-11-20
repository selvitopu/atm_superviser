package io.github.selvitopu.atmsup.supervisor.board;

import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.alarms.Via;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public sealed interface DomainEvent {

    EventType type();

    Instant occurredAt();

    /** Full new state of one device; type is EQUIPMENT_STATE_CHANGED, CONTACT_LOST or CONTACT_RESTORED. */
    record EquipmentEvent(EventType type, Instant occurredAt, EquipmentState state) implements DomainEvent {
        public EquipmentEvent {
            requireKind(type, EntityKind.EQUIPMENT);
        }
    }

    record ServiceEvent(Instant occurredAt, ServiceState state) implements DomainEvent {
        @Override
        public EventType type() {
            return EventType.SERVICE_STATE_CHANGED;
        }
    }

    record AlarmEvent(EventType type, Instant occurredAt, Alarm alarm, Via via, String actor)
            implements DomainEvent {
        public AlarmEvent {
            requireKind(type, EntityKind.ALARM);
        }
    }

    /** {@code reason} is set only for SUPERVISOR_STARTED. */
    record SupervisorEvent(EventType type, Instant occurredAt, UnknownReason reason) implements DomainEvent {
        public SupervisorEvent {
            requireKind(type, EntityKind.SUPERVISOR);
        }
    }

    /**
     * A user's command to a unit, as its intent (ISSUED) or its outcome (DONE, REFUSED, NO_ANSWER, FAILED or
     * NOT_SENT); the audit trail. The intent and the outcome of one command share its {@code commandId}, which is null
     * only for events written before commands had ids.
     */
    record CommandEvent(Instant occurredAt, String equipmentId, String command, String actor, String outcome,
                        String commandId) implements DomainEvent {
        @Override
        public EventType type() {
            return EventType.EQUIPMENT_COMMANDED;
        }
    }

    record TelemetrySampleEvent(Instant occurredAt, String equipmentId, Instant observedAt,
                                Map<String, Object> values, Map<String, List<Map<String, Object>>> tables)
            implements DomainEvent {
        @Override
        public EventType type() {
            return EventType.TELEMETRY_SAMPLED;
        }
    }

    /** The start or end of an alarm flood; {@code raisedInWindow} is the counted raises in the window. */
    record AlarmFloodEvent(EventType type, Instant occurredAt, int raisedInWindow) implements DomainEvent {
        public AlarmFloodEvent {
            requireKind(type, EntityKind.FLOOD);
        }
    }

    private static void requireKind(EventType type, EntityKind kind) {
        if (type == null || type.kind() != kind) {
            throw new IllegalArgumentException(type + " is not a " + kind + " event");
        }
    }
}
