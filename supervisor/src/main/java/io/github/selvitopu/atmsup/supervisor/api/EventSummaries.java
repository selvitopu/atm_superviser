package io.github.selvitopu.atmsup.supervisor.api;

import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.AlarmEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.AlarmFloodEvent;
import io.github.selvitopu.atmsup.supervisor.board.EventType;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.CommandEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.EquipmentEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.ServiceEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.SupervisorEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.TelemetrySampleEvent;
import io.github.selvitopu.atmsup.supervisor.board.EquipmentState;
import io.github.selvitopu.atmsup.supervisor.board.ServiceState;
import io.github.selvitopu.atmsup.supervisor.board.StoredEvent;
import java.util.Locale;

/** One-line English summaries of stored events, for the equipment detail view. */
public final class EventSummaries {

    private EventSummaries() {
    }

    public static String describe(StoredEvent stored) {
        return switch (stored.event()) {
            case EquipmentEvent e -> equipment(e);
            case ServiceEvent e -> service(e.state());
            case AlarmEvent e -> alarm(e);
            case SupervisorEvent e -> supervisor(e);
            case TelemetrySampleEvent e -> "Telemetry sampled";
            case AlarmFloodEvent e -> e.type() == EventType.ALARM_FLOOD_STARTED
                    ? "Alarm flood started (" + e.raisedInWindow() + " new alarms in the window)"
                    : "Alarm flood ended";
            case CommandEvent e -> command(e);
        };
    }

    /** "Maintenance on by supervisor1: issued", then "...: done", or another outcome such as "no answer". */
    private static String command(CommandEvent e) {
        String command = switch (e.command()) {
            case "MAINTENANCE_ON" -> "Maintenance on";
            case "MAINTENANCE_OFF" -> "Maintenance off";
            case "RESTART" -> "Restart";
            case "MAKE_ACTIVE" -> "Make active";
            default -> e.command();
        };
        return command + " by " + e.actor() + ": " + e.outcome().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String equipment(EquipmentEvent e) {
        return switch (e.type()) {
            case CONTACT_LOST -> "Lost contact";
            case CONTACT_RESTORED -> "Contact restored";
            default -> state(e.state());
        };
    }

    private static String state(EquipmentState s) {
        String health = s.unknownReason() == null ? s.health().name()
                : s.health().name() + " (" + s.unknownReason().name() + ")";
        return "Health " + health + " · role " + s.role().name()
                + " · maintenance " + (s.maintenance() ? "on" : "off");
    }

    private static String service(ServiceState s) {
        return s.id().label() + " " + s.status().name();
    }

    private static String alarm(AlarmEvent e) {
        Alarm a = e.alarm();
        int code = a.key().code();
        return switch (e.type()) {
            case ALARM_RAISED -> "Alarm raised: " + detail(a);
            case ALARM_UPDATED -> "Alarm updated: " + detail(a);
            case ALARM_ACKNOWLEDGED -> "Alarm acknowledged by " + e.actor() + ": " + code;
            case ALARM_CLEARED -> "Alarm cleared: " + code;
            case ALARM_CLOSED -> "Alarm closed: " + code;
            default -> throw new IllegalStateException("Not an alarm event: " + e.type());
        };
    }

    private static String detail(Alarm a) {
        return a.key().code() + " " + a.text() + " (" + a.key().equipmentId() + "), " + a.severity().name();
    }

    private static String supervisor(SupervisorEvent e) {
        return switch (e.type()) {
            case SUPERVISOR_STARTED -> "Supervisor started";
            case SUPERVISOR_HEARTBEAT -> "Supervisor heartbeat";
            case SUPERVISOR_STOPPING -> "Supervisor stopping";
            default -> throw new IllegalStateException("Not a supervisor event: " + e.type());
        };
    }
}
