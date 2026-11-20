package io.github.selvitopu.atmsup.supervisor.api;

import io.github.selvitopu.atmsup.supervisor.api.Dtos.CommandDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.FloodDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.SampleDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.SupervisorEventDto;
import io.github.selvitopu.atmsup.supervisor.board.Board;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.AlarmEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.AlarmFloodEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.CommandEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.EquipmentEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.ServiceEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.SupervisorEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.TelemetrySampleEvent;
import io.github.selvitopu.atmsup.supervisor.board.EventType;
import io.github.selvitopu.atmsup.supervisor.board.LiveBoard;
import io.github.selvitopu.atmsup.supervisor.board.StoredEvent;
import io.github.selvitopu.atmsup.supervisor.board.Telemetry;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Maps stored events to the messages of the live stream and of the replay feed, so both speak the same shapes. Live
 * messages carry the current telemetry and flag alarms of unknown units; replay messages carry no telemetry (the
 * browser keeps the sampled values) and never flag an alarm, as the alarm list derives that from the units' health.
 */
@Component
public class StreamMessages {

    private static final Board NO_UNITS = new Board(Map.of(), Map.of(), Map.of(), 0);

    private final DtoMapper mapper;

    public StreamMessages(DtoMapper mapper) {
        this.mapper = mapper;
    }

    /** Null for TELEMETRY_SAMPLED and EQUIPMENT_COMMANDED, which are stored only. */
    public SseMessage live(StoredEvent stored, LiveBoard live) {
        return map(stored, id -> live.telemetry().getOrDefault(id, Telemetry.EMPTY), live.board());
    }

    /** Every stored event has a message; samples and commands have the replay-only kinds {@code sample} and {@code command}. */
    public SseMessage replay(StoredEvent stored) {
        long id = stored.id();
        return switch (stored.event()) {
            case TelemetrySampleEvent e -> new SseMessage("sample", id,
                    new SampleDto(e.equipmentId(), e.observedAt(), e.values(), e.tables()));
            case CommandEvent e -> new SseMessage("command", id,
                    new CommandDto(e.equipmentId(), e.command(), e.actor(), e.outcome(), e.commandId()));
            default -> map(stored, unit -> Telemetry.EMPTY, NO_UNITS);
        };
    }

    private SseMessage map(StoredEvent stored, Function<String, Telemetry> telemetry, Board board) {
        long id = stored.id();
        return switch (stored.event()) {
            case EquipmentEvent e -> new SseMessage("equipment", id,
                    mapper.equipment(e.state(), telemetry.apply(e.state().id())));
            case ServiceEvent e -> new SseMessage("service", id, mapper.service(e.state()));
            case AlarmEvent e -> new SseMessage("alarm", id, mapper.alarm(e.alarm(), board));
            case SupervisorEvent e -> new SseMessage("supervisor", id,
                    new SupervisorEventDto(e.type().name(), e.occurredAt()));
            case AlarmFloodEvent e -> {
                boolean started = e.type() == EventType.ALARM_FLOOD_STARTED;
                yield new SseMessage("flood", id, new FloodDto(started, started ? e.occurredAt() : null));
            }
            case TelemetrySampleEvent e -> null;
            case CommandEvent e -> null; // the audit trail: stored and shown in the equipment's recent events
        };
    }
}
