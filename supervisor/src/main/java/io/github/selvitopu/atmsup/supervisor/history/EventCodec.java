package io.github.selvitopu.atmsup.supervisor.history;

import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.alarms.Via;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.AlarmEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.EquipmentEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.ServiceEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.SupervisorEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.TelemetrySampleEvent;
import io.github.selvitopu.atmsup.supervisor.board.EquipmentState;
import io.github.selvitopu.atmsup.supervisor.board.EventType;
import io.github.selvitopu.atmsup.supervisor.board.ServiceState;
import io.github.selvitopu.atmsup.supervisor.board.UnknownReason;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Maps domain events to event log rows and back. The payload is versioned JSON carrying the full new state of the
 * entity; the occurrence time, type and actor live in their own columns.
 */
@Component
public class EventCodec {

    static final int SCHEMA_VERSION = 1;

    private final JsonMapper mapper;

    public EventCodec(JsonMapper mapper) {
        // Telemetry values are untyped: read integral numbers back as Long, as the observation mapper produces them.
        this.mapper = mapper.rebuild().enable(DeserializationFeature.USE_LONG_FOR_INTS).build();
    }

    public EventLogEntity encode(DomainEvent e, Instant recordedAt) {
        return switch (e) {
            case EquipmentEvent ev -> row(ev, recordedAt, ev.state().id(), ev.state().id(), null,
                    new EquipmentPayload(SCHEMA_VERSION, ev.state()));
            case ServiceEvent ev -> row(ev, recordedAt, ev.state().id().name(), null, null,
                    new ServicePayload(SCHEMA_VERSION, ev.state()));
            case AlarmEvent ev -> row(ev, recordedAt, ev.alarm().id(), ev.alarm().key().equipmentId(), ev.actor(),
                    new AlarmPayload(SCHEMA_VERSION, ev.alarm(), ev.via()));
            case SupervisorEvent ev -> row(ev, recordedAt, null, null, null,
                    new SupervisorPayload(SCHEMA_VERSION, ev.reason()));
            case TelemetrySampleEvent ev -> row(ev, recordedAt, ev.equipmentId(), ev.equipmentId(), null,
                    new TelemetryPayload(SCHEMA_VERSION, ev.equipmentId(), ev.observedAt(), ev.values(), ev.tables()));
        };
    }

    /** @throws IllegalStateException if the payload has an unsupported schema version */
    public DomainEvent decode(EventLogEntity row) {
        EventType type = EventType.valueOf(row.getType());
        Instant at = row.getOccurredAt();
        return switch (type.kind()) {
            case EQUIPMENT -> new EquipmentEvent(type, at, read(row, EquipmentPayload.class).state());
            case SERVICE -> new ServiceEvent(at, read(row, ServicePayload.class).state());
            case ALARM -> {
                AlarmPayload p = read(row, AlarmPayload.class);
                yield new AlarmEvent(type, at, p.alarm(), p.via(), row.getActor());
            }
            case SUPERVISOR -> new SupervisorEvent(type, at, read(row, SupervisorPayload.class).reason());
            case TELEMETRY -> {
                TelemetryPayload p = read(row, TelemetryPayload.class);
                yield new TelemetrySampleEvent(at, p.equipmentId(), p.observedAt(), p.values(), p.tables());
            }
        };
    }

    private EventLogEntity row(DomainEvent e, Instant recordedAt, String entityId, String equipmentId, String actor,
                               Payload payload) {
        return new EventLogEntity(e.occurredAt(), recordedAt, e.type().name(), e.type().kind().name(), entityId,
                equipmentId, actor, mapper.writeValueAsString(payload));
    }

    private <T extends Payload> T read(EventLogEntity row, Class<T> type) {
        T p = mapper.readValue(row.getPayload(), type);
        if (p.schemaVersion() != SCHEMA_VERSION) {
            throw new IllegalStateException("Event " + row.getId() + " has payload schema version "
                    + p.schemaVersion() + "; supported is " + SCHEMA_VERSION);
        }
        return p;
    }

    private sealed interface Payload {
        int schemaVersion();
    }

    private record EquipmentPayload(int schemaVersion, EquipmentState state) implements Payload {
    }

    private record ServicePayload(int schemaVersion, ServiceState state) implements Payload {
    }

    private record AlarmPayload(int schemaVersion, Alarm alarm, Via via) implements Payload {
    }

    private record SupervisorPayload(int schemaVersion, UnknownReason reason) implements Payload {
    }

    private record TelemetryPayload(int schemaVersion, String equipmentId, Instant observedAt,
                                    Map<String, Object> values, Map<String, List<Map<String, Object>>> tables)
            implements Payload {
    }
}
