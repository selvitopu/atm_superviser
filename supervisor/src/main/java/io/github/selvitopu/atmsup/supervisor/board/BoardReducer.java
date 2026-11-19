package io.github.selvitopu.atmsup.supervisor.board;

import io.github.selvitopu.atmsup.supervisor.alarms.AlarmState;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.AlarmEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.CommandEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.EquipmentEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.ServiceEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.SupervisorEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.TelemetrySampleEvent;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure upsert fold of events onto a board: live board == fold(event_log) for durable state. */
public final class BoardReducer {

    private BoardReducer() {
    }

    public static Board apply(Board b, StoredEvent e) {
        Board next = apply(b, e.event());
        return new Board(next.equipment(), next.services(), next.openAlarms(), e.id());
    }

    public static Board apply(Board b, DomainEvent e) {
        return switch (e) {
            case EquipmentEvent ev -> {
                var eq = new LinkedHashMap<>(b.equipment());
                eq.put(ev.state().id(), ev.state());
                yield new Board(eq, b.services(), b.openAlarms(), b.lastEventId());
            }
            case ServiceEvent ev -> {
                var sv = new LinkedHashMap<>(b.services());
                sv.put(ev.state().id(), ev.state());
                yield new Board(b.equipment(), sv, b.openAlarms(), b.lastEventId());
            }
            case AlarmEvent ev -> {
                var al = new LinkedHashMap<>(b.openAlarms());
                if (ev.alarm().state() == AlarmState.CLOSED) {
                    al.remove(ev.alarm().key());
                } else {
                    al.put(ev.alarm().key(), ev.alarm());
                }
                yield new Board(b.equipment(), b.services(), al, b.lastEventId());
            }
            case SupervisorEvent ev -> ev.type() == EventType.SUPERVISOR_STARTED ? started(b, ev) : b;
            case TelemetrySampleEvent ev -> b;
            case CommandEvent ev -> b;
        };
    }

    private static Board started(Board b, SupervisorEvent ev) {
        UnknownReason reason = ev.reason() == null ? UnknownReason.SUPERVISOR_RESTARTED : ev.reason();
        Map<String, EquipmentState> eq = new LinkedHashMap<>();
        b.equipment().forEach((id, s) -> {
            var since = s.health() == Health.UNKNOWN ? s.healthSince() : ev.occurredAt();
            var c = s.contact();
            eq.put(id, new EquipmentState(id, Health.UNKNOWN, reason, s.maintenance(), s.role(), since,
                    new Contact(false, c.lastContactAt(), c.lostAt())));
        });
        return new Board(eq, b.services(), b.openAlarms(), b.lastEventId());
    }
}
