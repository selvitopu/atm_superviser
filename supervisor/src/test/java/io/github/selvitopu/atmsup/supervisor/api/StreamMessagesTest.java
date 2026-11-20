package io.github.selvitopu.atmsup.supervisor.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.simulator.fixture.TestInventories;
import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmKey;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmSource;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmState;
import io.github.selvitopu.atmsup.supervisor.alarms.Via;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.AlarmDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.CommandDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.EquipmentDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.FloodDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.SampleDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.SupervisorEventDto;
import io.github.selvitopu.atmsup.supervisor.board.Board;
import io.github.selvitopu.atmsup.supervisor.board.Contact;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.AlarmEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.AlarmFloodEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.CommandEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.EquipmentEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.SupervisorEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.TelemetrySampleEvent;
import io.github.selvitopu.atmsup.supervisor.board.EquipmentState;
import io.github.selvitopu.atmsup.supervisor.board.EventType;
import io.github.selvitopu.atmsup.supervisor.board.Health;
import io.github.selvitopu.atmsup.supervisor.board.LiveBoard;
import io.github.selvitopu.atmsup.supervisor.board.StoredEvent;
import io.github.selvitopu.atmsup.supervisor.board.UnknownReason;
import io.github.selvitopu.atmsup.supervisor.board.Telemetry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StreamMessagesTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    private final Inventory inventory = Inventory.from(TestInventories.v01(16101, 10162));
    private final StreamMessages messages = new StreamMessages(new DtoMapper(inventory));
    private final Telemetry telemetry = new Telemetry(T0.minusSeconds(1), T0, 2, 0, null,
            Map.of("plotsPerScan", 300L), Map.of("sensors", List.of(Map.of("index", 1L))));

    private static EquipmentState unknownRadar() {
        return new EquipmentState("RAD-N", Health.UNKNOWN, UnknownReason.LOST_CONTACT, false, Role.STANDALONE, T0,
                new Contact(false, T0, T0));
    }

    private LiveBoard live() {
        Board board = new Board(Map.of("RAD-N", unknownRadar()), Map.of(), Map.of(), 7);
        return new LiveBoard(board, Map.of("RAD-N", telemetry), Map.of(), Map.of());
    }

    private static Alarm alarm() {
        return new Alarm("a1", new AlarmKey("RAD-N", 101, 0), "", Severity.CRITICAL, "Radar failure",
                AlarmSource.DEVICE, AlarmState.ACTIVE_UNACK, false, T0, null, null, null, null);
    }

    @Test
    void liveAndReplayShapesMatchPerKind() {
        StoredEvent equipment = new StoredEvent(1, new EquipmentEvent(EventType.EQUIPMENT_STATE_CHANGED, T0,
                unknownRadar()));
        StoredEvent alarm = new StoredEvent(2, new AlarmEvent(EventType.ALARM_RAISED, T0, alarm(), Via.TRAP, null));
        StoredEvent supervisor = new StoredEvent(3, new SupervisorEvent(EventType.SUPERVISOR_STARTED, T0, null));
        StoredEvent flood = new StoredEvent(4, new AlarmFloodEvent(EventType.ALARM_FLOOD_STARTED, T0, 5));

        for (StoredEvent e : List.of(equipment, alarm, supervisor, flood)) {
            SseMessage live = messages.live(e, live());
            SseMessage replay = messages.replay(e);
            assertThat(replay.name()).isEqualTo(live.name());
            assertThat(replay.id()).isEqualTo(live.id());
            assertThat(replay.data()).hasSameClassAs(live.data());
        }
        assertThat(messages.replay(supervisor).data()).isEqualTo(new SupervisorEventDto("SUPERVISOR_STARTED", T0));
        assertThat(messages.replay(flood).data()).isEqualTo(new FloodDto(true, T0));

        // live keeps the telemetry and the unconfirmed flag; replay has no telemetry and never flags an alarm
        EquipmentDto liveUnit = (EquipmentDto) messages.live(equipment, live()).data();
        EquipmentDto replayUnit = (EquipmentDto) messages.replay(equipment).data();
        assertThat(liveUnit.values()).containsEntry("plotsPerScan", 300L);
        assertThat(liveUnit.contact().consecutiveFailures()).isEqualTo(2);
        assertThat(replayUnit.values()).isEmpty();
        assertThat(replayUnit.tables()).isEmpty();
        assertThat(replayUnit.contact().lastPollAt()).isNull();
        assertThat(replayUnit.contact().consecutiveFailures()).isZero();
        assertThat(replayUnit.sampledAt()).isNull();
        assertThat(((AlarmDto) messages.live(alarm, live()).data()).unconfirmed()).isTrue();
        assertThat(((AlarmDto) messages.replay(alarm).data()).unconfirmed()).isFalse();
    }

    @Test
    void samplesAndCommandsMapToTheirReplayKinds() {
        StoredEvent sample = new StoredEvent(5, new TelemetrySampleEvent(T0, "RAD-N", T0.minusSeconds(3),
                Map.of("plotsPerScan", 300L), Map.of()));
        StoredEvent command = new StoredEvent(6, new CommandEvent(T0, "RAD-N", "RESTART", "supervisor1", "DONE", "c-1"));

        assertThat(messages.live(sample, live())).isNull();
        assertThat(messages.live(command, live())).isNull();
        assertThat(messages.replay(sample)).isEqualTo(new SseMessage("sample", 5L,
                new SampleDto("RAD-N", T0.minusSeconds(3), Map.of("plotsPerScan", 300L), Map.of())));
        assertThat(messages.replay(command)).isEqualTo(new SseMessage("command", 6L,
                new CommandDto("RAD-N", "RESTART", "supervisor1", "DONE", "c-1")));
    }
}
