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

    record TelemetrySampleEvent(Instant occurredAt, String equipmentId, Instant observedAt,
                                Map<String, Object> values, Map<String, List<Map<String, Object>>> tables)
            implements DomainEvent {
        @Override
        public EventType type() {
            return EventType.TELEMETRY_SAMPLED;
        }
    }

    private static void requireKind(EventType type, EntityKind kind) {
        if (type == null || type.kind() != kind) {
            throw new IllegalArgumentException(type + " is not a " + kind + " event");
        }
    }
}
