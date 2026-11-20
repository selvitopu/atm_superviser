package io.github.selvitopu.atmsup.supervisor.board;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmEventType;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmKey;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmSource;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmState;
import io.github.selvitopu.atmsup.supervisor.alarms.Via;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.AlarmEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.AlarmFloodEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.CommandEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.EquipmentEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.ServiceEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.SupervisorEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.TelemetrySampleEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BoardReducerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final Board initial = Board.initial(BoardFixtures.inventory(false), List.of());

    private static Alarm alarm(AlarmState state) {
        var key = new AlarmKey("RAD-N", 101, 0);
        return new Alarm("a1", key, "", Severity.MAJOR, "text", AlarmSource.DEVICE, state, false, NOW, null, null,
                null, null);
    }

    @Test
    void initialBoardIsUnknownWithRolesFromInventory() {
        assertThat(initial.equipment()).hasSize(7);
        assertThat(initial.equipment().keySet()).containsExactly("RAD-N", "RAD-E", "RAD-S", "SDPS-A", "SDPS-B",
                "FDPS-A", "FDPS-B");
        initial.equipment().values().forEach(s -> {
            assertThat(s.health()).isEqualTo(Health.UNKNOWN);
            assertThat(s.unknownReason()).isEqualTo(UnknownReason.NOT_YET_POLLED);
            assertThat(s.maintenance()).isFalse();
            assertThat(s.contact()).isEqualTo(new Contact(false, null, null));
        });
        assertThat(initial.equipment().get("RAD-N").role()).isEqualTo(Role.STANDALONE);
        assertThat(initial.equipment().get("SDPS-A").role()).isEqualTo(Role.ACTIVE);
        assertThat(initial.equipment().get("SDPS-B").role()).isEqualTo(Role.STANDBY);
        assertThat(initial.equipment().get("FDPS-B").role()).isEqualTo(Role.STANDBY);
        assertThat(initial.services()).isEmpty();
        assertThat(initial.lastEventId()).isZero();
    }

    @Test
    void initialBoardCarriesOpenAlarms() {
        var board = Board.initial(BoardFixtures.inventory(false), List.of(alarm(AlarmState.ACTIVE_UNACK)));
        assertThat(board.openAlarms()).containsOnlyKeys(new AlarmKey("RAD-N", 101, 0));
    }

    @Test
    void equipmentEventPutsItsState() {
        var s = BoardFixtures.known("RAD-N", Health.OPERATIONAL);
        var b = BoardReducer.apply(initial, new EquipmentEvent(EventType.EQUIPMENT_STATE_CHANGED, NOW, s));
        assertThat(b.equipment().get("RAD-N")).isEqualTo(s);
        assertThat(b.equipment().keySet()).containsExactlyElementsOf(initial.equipment().keySet());
    }

    @Test
    void serviceEventPutsItsState() {
        var s = new ServiceState(ServiceId.SURV, ServiceStatus.OK, null, false, false, List.of(), NOW);
        var b = BoardReducer.apply(initial, new ServiceEvent(NOW, s));
        assertThat(b.services()).containsEntry(ServiceId.SURV, s);
    }

    @Test
    void alarmEventsPutThenClosedRemovesKey() {
        var opened = BoardReducer.apply(initial, new AlarmEvent(EventType.ofAlarm(AlarmEventType.RAISED), NOW,
                alarm(AlarmState.ACTIVE_UNACK), Via.TRAP, null));
        assertThat(opened.openAlarms()).hasSize(1);
        var acked = BoardReducer.apply(opened, new AlarmEvent(EventType.ALARM_ACKNOWLEDGED, NOW,
                alarm(AlarmState.ACTIVE_ACK), Via.OPERATOR, "demo-user"));
        assertThat(acked.openAlarms().values()).extracting(Alarm::state).containsExactly(AlarmState.ACTIVE_ACK);
        var closed = BoardReducer.apply(acked, new AlarmEvent(EventType.ALARM_CLOSED, NOW,
                alarm(AlarmState.CLOSED), Via.OPERATOR, "demo-user"));
        assertThat(closed.openAlarms()).isEmpty();
    }

    @Test
    void supervisorStartedMarksAllUnknownKeepingMaintenanceAndRole() {
        var s = new EquipmentState("RAD-N", Health.OPERATIONAL, null, true, Role.STANDALONE, NOW,
                new Contact(true, NOW, null));
        var running = BoardReducer.apply(initial, new EquipmentEvent(EventType.EQUIPMENT_STATE_CHANGED, NOW, s));
        var later = NOW.plusSeconds(60);
        var b = BoardReducer.apply(running, new SupervisorEvent(EventType.SUPERVISOR_STARTED, later,
                UnknownReason.SUPERVISOR_RESTARTED));
        var rad = b.equipment().get("RAD-N");
        assertThat(rad.health()).isEqualTo(Health.UNKNOWN);
        assertThat(rad.unknownReason()).isEqualTo(UnknownReason.SUPERVISOR_RESTARTED);
        assertThat(rad.maintenance()).isTrue();
        assertThat(rad.role()).isEqualTo(Role.STANDALONE);
        assertThat(rad.contact().reachable()).isFalse();
        assertThat(rad.contact().lastContactAt()).isEqualTo(NOW);
        assertThat(rad.healthSince()).isEqualTo(later);
        assertThat(b.equipment().values()).allSatisfy(e -> assertThat(e.health()).isEqualTo(Health.UNKNOWN));
        assertThat(b.equipment().get("SDPS-B").role()).isEqualTo(Role.STANDBY);
    }

    @Test
    void heartbeatAndStoppingChangeNothing() {
        assertThat(BoardReducer.apply(initial, new SupervisorEvent(EventType.SUPERVISOR_HEARTBEAT, NOW, null)))
                .isEqualTo(initial);
        assertThat(BoardReducer.apply(initial, new SupervisorEvent(EventType.SUPERVISOR_STOPPING, NOW, null)))
                .isEqualTo(initial);
    }

    @Test
    void telemetrySampleChangesNoBoardState() {
        var ev = new TelemetrySampleEvent(NOW, "RAD-N", NOW, Map.of("rate", 1), Map.of());
        var b = BoardReducer.apply(initial, new StoredEvent(7, ev));
        assertThat(b).isEqualTo(new Board(initial.equipment(), initial.services(), initial.openAlarms(), 7));
    }

    private final Board flooded = new Board(initial.equipment(), initial.services(), initial.openAlarms(), 3,
            new AlarmFlood(true, NOW));

    @Test
    void floodEventsSetAndClearTheFlood() {
        var started = BoardReducer.apply(initial,
                new AlarmFloodEvent(EventType.ALARM_FLOOD_STARTED, NOW, 10));
        assertThat(started.flood()).isEqualTo(new AlarmFlood(true, NOW));
        var ended = BoardReducer.apply(started,
                new AlarmFloodEvent(EventType.ALARM_FLOOD_ENDED, NOW.plusSeconds(60), 4));
        assertThat(ended.flood()).isEqualTo(AlarmFlood.NONE);
        assertThat(initial.flood()).isEqualTo(AlarmFlood.NONE);
    }

    @Test
    void supervisorStartedEndsTheFlood() {
        var b = BoardReducer.apply(flooded, new SupervisorEvent(EventType.SUPERVISOR_STARTED, NOW.plusSeconds(60),
                UnknownReason.SUPERVISOR_RESTARTED));
        assertThat(b.flood()).isEqualTo(AlarmFlood.NONE);
    }

    @Test
    void everyOtherEventKeepsTheFlood() {
        var eq = new EquipmentEvent(EventType.EQUIPMENT_STATE_CHANGED, NOW,
                BoardFixtures.known("RAD-N", Health.OPERATIONAL));
        var sv = new ServiceEvent(NOW, new ServiceState(ServiceId.SURV, ServiceStatus.OK, null, false, false,
                List.of(), NOW));
        var raised = new AlarmEvent(EventType.ALARM_RAISED, NOW, alarm(AlarmState.ACTIVE_UNACK), Via.TRAP, null);
        var closed = new AlarmEvent(EventType.ALARM_CLOSED, NOW, alarm(AlarmState.CLOSED), Via.TRAP, null);
        var heartbeat = new SupervisorEvent(EventType.SUPERVISOR_HEARTBEAT, NOW, null);
        var stopping = new SupervisorEvent(EventType.SUPERVISOR_STOPPING, NOW, null);
        var sample = new TelemetrySampleEvent(NOW, "RAD-N", NOW, Map.of(), Map.of());
        var command = new CommandEvent(NOW, "RAD-N", "MAINTENANCE_ON", "supervisor1", "DONE", "c-1");
        for (DomainEvent e : List.<DomainEvent>of(eq, sv, raised, closed, heartbeat, stopping, sample, command)) {
            assertThat(BoardReducer.apply(flooded, e).flood()).as(e.type().name()).isEqualTo(flooded.flood());
            assertThat(BoardReducer.apply(flooded, new StoredEvent(9, e)).flood()).as(e.type().name())
                    .isEqualTo(flooded.flood());
        }
    }

    @Test
    void storedEventSetsLastEventId() {
        var ev = new SupervisorEvent(EventType.SUPERVISOR_HEARTBEAT, NOW, null);
        assertThat(BoardReducer.apply(initial, new StoredEvent(42, ev)).lastEventId()).isEqualTo(42);
    }

    @Test
    void eventTypesMapToKindsAndAlarmTypes() {
        assertThat(EventType.CONTACT_LOST.kind()).isEqualTo(EntityKind.EQUIPMENT);
        assertThat(EventType.TELEMETRY_SAMPLED.kind()).isEqualTo(EntityKind.TELEMETRY);
        assertThat(EventType.ofAlarm(AlarmEventType.CLEARED)).isEqualTo(EventType.ALARM_CLEARED);
        assertThat(new ServiceEvent(NOW,
                new ServiceState(ServiceId.FD, ServiceStatus.OK, null, false, false, List.of(), NOW))
                .type()).isEqualTo(EventType.SERVICE_STATE_CHANGED);
    }

    @Test
    void liveBoardGenerationDefaultsToZero() {
        var live = new LiveBoard(initial, Map.of(), Map.of("RAD-N", 3L), Map.of("RAD-N", 2L));
        assertThat(live.generation("RAD-N")).isEqualTo(3);
        assertThat(live.generation("RAD-E")).isZero();
        assertThat(live.contactGeneration("RAD-N")).isEqualTo(2);
        assertThat(live.contactGeneration("RAD-E")).isZero();
    }
}
