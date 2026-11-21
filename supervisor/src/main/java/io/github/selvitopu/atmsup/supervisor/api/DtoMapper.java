package io.github.selvitopu.atmsup.supervisor.api;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.AlarmDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.BoardDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.ContactDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.CorrelationDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.EquipmentDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.FloodDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.ReplayBoardDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.ServiceDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.TelemetryDto;
import io.github.selvitopu.atmsup.supervisor.board.Board;
import io.github.selvitopu.atmsup.supervisor.board.EquipmentState;
import io.github.selvitopu.atmsup.supervisor.board.Health;
import io.github.selvitopu.atmsup.supervisor.board.LiveBoard;
import io.github.selvitopu.atmsup.supervisor.board.ServiceState;
import io.github.selvitopu.atmsup.supervisor.board.Telemetry;
import io.github.selvitopu.atmsup.supervisor.history.ReplayBoard;
import io.github.selvitopu.atmsup.supervisor.history.Sample;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/** Maps the board and its entities to the API's DTOs. Stateless apart from the inventory. */
@Component
public class DtoMapper {

    private static final Comparator<Alarm> MOST_SEVERE_THEN_NEWEST = (a, b) -> {
        if (a.severity() != b.severity()) {
            return a.severity().moreSevereThan(b.severity()) ? -1 : 1;
        }
        return b.raisedAt().compareTo(a.raisedAt());
    };

    private final Inventory inventory;

    public DtoMapper(Inventory inventory) {
        this.inventory = inventory;
    }

    /** Equipment in inventory order, services in enum order, alarms by severity then newest first. */
    public BoardDto board(LiveBoard lb, String centre, Instant now) {
        return board(lb.board(), centre, now, d -> equipment(lb, d.id()), lb.board());
    }

    /**
     * One layout for both boards: {@code unit} maps a device, and {@code alarmContext} is the board against which an
     * alarm is flagged unconfirmed.
     */
    private BoardDto board(Board b, String centre, Instant serverTime, Function<Device, EquipmentDto> unit,
                           Board alarmContext) {
        List<ServiceDto> services = b.services().values().stream()
                .sorted(Comparator.comparing(s -> s.id().ordinal()))
                .map(this::service).toList();
        List<EquipmentDto> equipment = inventory.devices().stream().map(unit).toList();
        List<AlarmDto> alarms = b.openAlarms().values().stream().sorted(MOST_SEVERE_THEN_NEWEST)
                .map(a -> alarm(a, alarmContext)).toList();
        return new BoardDto(centre, serverTime, b.lastEventId(), services, equipment, alarms,
                new FloodDto(b.flood().active(), b.flood().since()));
    }

    /** @throws java.util.NoSuchElementException if the inventory has no such device */
    public EquipmentDto equipment(LiveBoard lb, String id) {
        inventory.device(id); // unknown ids fail here, before the board is read
        return equipment(lb.board().equipment().get(id), lb.telemetry().getOrDefault(id, Telemetry.EMPTY));
    }

    /** One device's state (for example the one an event carries) with its live telemetry. */
    public EquipmentDto equipment(EquipmentState s, Telemetry t) {
        Device d = inventory.device(s.id());
        ContactDto contact = new ContactDto(s.contact().reachable(), s.contact().lastContactAt(),
                s.contact().lostAt(), t.lastPollAt(), t.consecutiveFailures(), d.pollInterval().toMillis());
        return new EquipmentDto(s.id(), d.name(), d.type().name(), d.group().name(), d.pair(), s.health().name(),
                s.unknownReason() == null ? null : s.unknownReason().name(), s.maintenance(), s.role().name(),
                s.healthSince(), contact, t.values(), t.tables(), null);
    }

    /**
     * The board of a replay: units in inventory order with the values of their newest sample at the instant (none
     * when there is none), {@code sampledAt} set, no poll time and no failures. Alarms are never flagged unconfirmed.
     */
    public ReplayBoardDto replayBoard(ReplayBoard rb, String centre, long gapThresholdSeconds) {
        Board b = rb.board();
        BoardDto board = board(b, centre, rb.at(), d -> {
            EquipmentDto e = equipment(b.equipment().get(d.id()), Telemetry.EMPTY);
            Sample s = rb.samples().get(d.id());
            return s == null ? e : new EquipmentDto(e.id(), e.name(), e.type(), e.group(), e.pair(), e.health(),
                    e.unknownReason(), e.maintenance(), e.role(), e.healthSince(), e.contact(), s.values(),
                    s.tables(), s.observedAt());
        }, new Board(Map.of(), Map.of(), Map.of(), 0));
        return new ReplayBoardDto(rb.at(), rb.noData(), gapThresholdSeconds, board);
    }

    public TelemetryDto telemetry(String equipmentId, Telemetry t, Instant serverTime) {
        return new TelemetryDto(equipmentId, t.lastPollAt(), t.consecutiveFailures(), t.values(), t.tables(),
                serverTime);
    }

    public AlarmDto alarm(Alarm a, Board b) {
        EquipmentState eq = b.equipment().get(a.key().equipmentId());
        boolean unconfirmed = eq != null && eq.health() == Health.UNKNOWN;
        return new AlarmDto(a.id(), a.key().equipmentId(), a.key().code(), a.key().instance(), a.instanceName(),
                a.severity().name(), a.text(), a.source().name(), a.state().name(), a.suppressed(), unconfirmed,
                a.raisedAt(), a.ackedAt(), a.ackedBy(), a.ackComment(), a.clearedAt(),
                a.correlation() == null ? null : new CorrelationDto(a.correlation().causeId(),
                        a.correlation().causeEquipmentId(), a.correlation().causeCode(),
                        a.correlation().rule().name()));
    }

    public ServiceDto service(ServiceState s) {
        return new ServiceDto(s.id().name(), s.id().label(), s.status().name(),
                s.bestCase() == null ? null : s.bestCase().name(), s.redundancyLost(), s.reasons(), s.since());
    }
}
