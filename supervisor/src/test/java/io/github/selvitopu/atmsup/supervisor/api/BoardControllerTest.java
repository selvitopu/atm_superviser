package io.github.selvitopu.atmsup.supervisor.api;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.selvitopu.atmsup.inventory.DeviceProperties;
import io.github.selvitopu.atmsup.inventory.DeviceType;
import io.github.selvitopu.atmsup.inventory.Group;
import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.inventory.InventoryProperties;
import io.github.selvitopu.atmsup.inventory.TrapTarget;
import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmKey;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmSource;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmState;
import io.github.selvitopu.atmsup.supervisor.board.Board;
import io.github.selvitopu.atmsup.supervisor.board.BoardWriter;
import io.github.selvitopu.atmsup.supervisor.board.Contact;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.EquipmentEvent;
import io.github.selvitopu.atmsup.supervisor.board.EquipmentState;
import io.github.selvitopu.atmsup.supervisor.board.EventType;
import io.github.selvitopu.atmsup.supervisor.board.Health;
import io.github.selvitopu.atmsup.supervisor.board.LiveBoard;
import io.github.selvitopu.atmsup.supervisor.board.ServiceId;
import io.github.selvitopu.atmsup.supervisor.board.ServiceState;
import io.github.selvitopu.atmsup.supervisor.board.ServiceStatus;
import io.github.selvitopu.atmsup.supervisor.board.StoredEvent;
import io.github.selvitopu.atmsup.supervisor.board.Telemetry;
import io.github.selvitopu.atmsup.supervisor.board.UnknownReason;
import io.github.selvitopu.atmsup.supervisor.history.EventQueries;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BoardController.class)
@Import({DtoMapper.class, BoardControllerTest.Inventories.class})
class BoardControllerTest {

    private static final Instant T = Instant.parse("2026-01-01T10:00:00Z");

    /** A real inventory: the mapper only reads it, and the order and poll interval are part of what is tested. */
    @TestConfiguration
    static class Inventories {
        @Bean
        Inventory inventory() {
            List<DeviceProperties> d = new ArrayList<>();
            d.add(new DeviceProperties("RAD-N", DeviceType.RADAR, "North radar", Group.SURVEILLANCE, null, 16101,
                    null, null));
            d.add(new DeviceProperties("SDPS-A", DeviceType.SDPS, "SDPS A", Group.SURVEILLANCE, null, 16104,
                    "SDPS-B", null));
            d.add(new DeviceProperties("SDPS-B", DeviceType.SDPS, "SDPS B", Group.SURVEILLANCE, null, 16105,
                    "SDPS-A", null));
            return Inventory.from(new InventoryProperties("Demo ACC", "127.0.0.1", Duration.ofSeconds(5),
                    new TrapTarget("127.0.0.1", 10162), d));
        }
    }

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private BoardWriter writer;
    @MockitoBean
    private EventQueries queries;

    private static EquipmentState state(String id, Health h, UnknownReason r, Role role) {
        return new EquipmentState(id, h, r, false, role, T, new Contact(h != Health.UNKNOWN, T, null));
    }

    private static Alarm alarm(String id, String equipment, int code, Severity s, Instant raisedAt) {
        return new Alarm(id, new AlarmKey(equipment, code, 1), "", s, "Text " + code, AlarmSource.DEVICE,
                AlarmState.ACTIVE_UNACK, false, raisedAt, null, null, null, null);
    }

    private void board(Map<String, EquipmentState> eq, List<Alarm> alarms, Map<String, Telemetry> telemetry) {
        Map<AlarmKey, Alarm> open = new LinkedHashMap<>();
        alarms.forEach(a -> open.put(a.key(), a));
        Map<ServiceId, ServiceState> services = Map.of(ServiceId.SURV,
                new ServiceState(ServiceId.SURV, ServiceStatus.OK, null, false, List.of(), T));
        given(writer.current())
                .willReturn(new LiveBoard(new Board(eq, services, open, 17), telemetry, Map.of(), Map.of()));
    }

    private static Map<String, EquipmentState> allKnown() {
        Map<String, EquipmentState> eq = new LinkedHashMap<>();
        eq.put("RAD-N", state("RAD-N", Health.OPERATIONAL, null, Role.STANDALONE));
        eq.put("SDPS-A", state("SDPS-A", Health.DEGRADED, null, Role.ACTIVE));
        eq.put("SDPS-B", state("SDPS-B", Health.OPERATIONAL, null, Role.STANDBY));
        return eq;
    }

    @Test
    void boardListsEquipmentInInventoryOrderWithTelemetry() throws Exception {
        Telemetry t = new Telemetry(T.minusSeconds(2), T.minusSeconds(1), 2, 0, null,
                Map.of("plotsPerScan", 300L), Map.of("sensors", List.of(Map.of("index", 1L))));
        board(allKnown(), List.of(), Map.of("RAD-N", t));

        mvc.perform(get("/api/v1/board")).andExpect(status().isOk())
                .andExpect(jsonPath("$.centre").value("Demo ACC"))
                .andExpect(jsonPath("$.lastEventId").value(17))
                .andExpect(jsonPath("$.services[0].id").value("SURV"))
                .andExpect(jsonPath("$.services[0].name").value("Surveillance"))
                .andExpect(jsonPath("$.services[0].status").value("OK"))
                .andExpect(jsonPath("$.equipment[0].id").value("RAD-N"))
                .andExpect(jsonPath("$.equipment[1].id").value("SDPS-A"))
                .andExpect(jsonPath("$.equipment[2].id").value("SDPS-B"))
                .andExpect(jsonPath("$.equipment[0].name").value("North radar"))
                .andExpect(jsonPath("$.equipment[0].type").value("RADAR"))
                .andExpect(jsonPath("$.equipment[0].group").value("SURVEILLANCE"))
                .andExpect(jsonPath("$.equipment[1].pair").value("SDPS-B"))
                .andExpect(jsonPath("$.equipment[0].health").value("OPERATIONAL"))
                .andExpect(jsonPath("$.equipment[0].contact.reachable").value(true))
                .andExpect(jsonPath("$.equipment[0].contact.pollIntervalMs").value(5000))
                .andExpect(jsonPath("$.equipment[0].contact.consecutiveFailures").value(2))
                .andExpect(jsonPath("$.equipment[0].contact.lastPollAt").value("2026-01-01T09:59:58Z"))
                .andExpect(jsonPath("$.equipment[0].values.plotsPerScan").value(300))
                .andExpect(jsonPath("$.equipment[0].tables.sensors[0].index").value(1))
                .andExpect(jsonPath("$.equipment[1].values").isEmpty());
    }

    @Test
    void alarmsAreSortedBySeverityThenNewestFirst() throws Exception {
        board(allKnown(), List.of(
                alarm("minor", "RAD-N", 401, Severity.MINOR, T.plusSeconds(50)),
                alarm("critical-old", "SDPS-A", 101, Severity.CRITICAL, T.plusSeconds(10)),
                alarm("critical-new", "SDPS-B", 101, Severity.CRITICAL, T.plusSeconds(20)),
                alarm("major", "SDPS-A", 301, Severity.MAJOR, T.plusSeconds(30))), Map.of());

        mvc.perform(get("/api/v1/board")).andExpect(status().isOk())
                .andExpect(jsonPath("$.alarms[0].id").value("critical-new"))
                .andExpect(jsonPath("$.alarms[1].id").value("critical-old"))
                .andExpect(jsonPath("$.alarms[2].id").value("major"))
                .andExpect(jsonPath("$.alarms[3].id").value("minor"))
                .andExpect(jsonPath("$.alarms[3].severity").value("MINOR"))
                .andExpect(jsonPath("$.alarms[3].equipmentId").value("RAD-N"))
                .andExpect(jsonPath("$.alarms[3].code").value(401))
                .andExpect(jsonPath("$.alarms[3].state").value("ACTIVE_UNACK"));
    }

    @Test
    void alarmOnUnknownEquipmentIsUnconfirmed() throws Exception {
        Map<String, EquipmentState> eq = allKnown();
        eq.put("RAD-N", state("RAD-N", Health.UNKNOWN, UnknownReason.LOST_CONTACT, Role.STANDALONE));
        board(eq, List.of(alarm("a1", "RAD-N", 201, Severity.MAJOR, T),
                alarm("a2", "SDPS-A", 301, Severity.MAJOR, T.minusSeconds(5))), Map.of());

        mvc.perform(get("/api/v1/board")).andExpect(status().isOk())
                .andExpect(jsonPath("$.alarms[0].id").value("a1"))
                .andExpect(jsonPath("$.alarms[0].unconfirmed").value(true))
                .andExpect(jsonPath("$.alarms[1].unconfirmed").value(false))
                .andExpect(jsonPath("$.equipment[0].health").value("UNKNOWN"))
                .andExpect(jsonPath("$.equipment[0].unknownReason").value("LOST_CONTACT"));
    }

    @Test
    void equipmentDetailListsRecentEvents() throws Exception {
        board(allKnown(), List.of(), Map.of());
        var ev = new EquipmentEvent(EventType.CONTACT_LOST, T,
                state("RAD-N", Health.UNKNOWN, UnknownReason.LOST_CONTACT, Role.STANDALONE));
        given(queries.recentForEquipment(eq("RAD-N"), eq(50))).willReturn(List.of(new StoredEvent(9, ev)));

        mvc.perform(get("/api/v1/equipment/RAD-N")).andExpect(status().isOk())
                .andExpect(jsonPath("$.equipment.id").value("RAD-N"))
                .andExpect(jsonPath("$.recentEvents[0].id").value(9))
                .andExpect(jsonPath("$.recentEvents[0].type").value("CONTACT_LOST"))
                .andExpect(jsonPath("$.recentEvents[0].summary").value("Lost contact"))
                .andExpect(jsonPath("$.recentEvents[0].occurredAt").value("2026-01-01T10:00:00Z"));
    }

    @Test
    void unknownEquipmentIs404Problem() throws Exception {
        board(allKnown(), List.of(), Map.of());
        mvc.perform(get("/api/v1/equipment/X-1")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not found"))
                .andExpect(jsonPath("$.detail").value("Unknown equipment X-1"));
        verify(queries, never()).recentForEquipment(anyString(), anyInt());
    }
}
