package io.github.selvitopu.atmsup.supervisor.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.simulator.fixture.TestInventories;
import io.github.selvitopu.atmsup.supervisor.board.Board;
import io.github.selvitopu.atmsup.supervisor.board.Contact;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.CommandEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.SupervisorEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.TelemetrySampleEvent;
import io.github.selvitopu.atmsup.supervisor.board.EquipmentState;
import io.github.selvitopu.atmsup.supervisor.board.EventType;
import io.github.selvitopu.atmsup.supervisor.board.Health;
import io.github.selvitopu.atmsup.supervisor.history.BoardHistory;
import io.github.selvitopu.atmsup.supervisor.history.EventCodec;
import io.github.selvitopu.atmsup.supervisor.history.EventLogEntity;
import io.github.selvitopu.atmsup.supervisor.history.EventLogRepository;
import io.github.selvitopu.atmsup.supervisor.history.ExportWriter;
import io.github.selvitopu.atmsup.supervisor.history.GapRule;
import io.github.selvitopu.atmsup.supervisor.history.ReplayBoard;
import io.github.selvitopu.atmsup.supervisor.history.Sample;
import io.github.selvitopu.atmsup.supervisor.history.Timeline;
import io.github.selvitopu.atmsup.supervisor.history.TimelineQuery;
import io.github.selvitopu.atmsup.supervisor.security.SecurityConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReplayController.class)
@Import({ReplayControllerTest.Beans.class, SecurityConfig.class, DtoMapper.class, StreamMessages.class,
        ExportWriter.class})
class ReplayControllerTest {

    private static final Instant T = Instant.parse("2026-01-01T10:00:00Z");

    @TestConfiguration
    static class Beans {
        @Bean
        Inventory inventory() {
            return Inventory.from(TestInventories.v01(16101, 10162));
        }

        @Bean
        java.time.InstantSource clock() {
            return java.time.InstantSource.fixed(Instant.parse("2026-01-02T00:00:00Z"));
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private Inventory inventory;
    @MockitoBean
    private BoardHistory history;
    @MockitoBean
    private EventLogRepository events;
    @MockitoBean
    private EventCodec codec;
    @MockitoBean
    private TimelineQuery timeline;

    private EventLogEntity row(long id, DomainEvent e) {
        EventLogEntity row = org.mockito.Mockito.mock(EventLogEntity.class);
        given(row.getId()).willReturn(id);
        given(codec.decode(row)).willReturn(e);
        return row;
    }

    @BeforeEach
    void fakes() {
        given(history.gapRule()).willReturn(new GapRule(Duration.ofSeconds(90)));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void boardAtReturnsTheReplayBoard() throws Exception {
        Board board = Board.initial(inventory, List.of());
        EquipmentState radar = new EquipmentState("RAD-N", Health.FAILED, null, false, Role.STANDALONE, T,
                new Contact(true, T, null));
        Map<String, EquipmentState> units = new LinkedHashMap<>(board.equipment());
        units.put("RAD-N", radar);
        Board withRadar = new Board(units, board.services(), board.openAlarms(), 42);
        Sample sample = new Sample("RAD-N", T.minusSeconds(20), Map.of("plotsPerScan", 300L), Map.of());
        given(history.boardAt(T)).willReturn(new ReplayBoard(T, withRadar, Map.of("RAD-N", sample), false,
                T.minusSeconds(80), "SUPERVISOR_HEARTBEAT"));

        mvc.perform(get("/api/v1/history/board").param("at", "2026-01-01T10:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.at").value("2026-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.noData").value(false))
                .andExpect(jsonPath("$.gapThresholdSeconds").value(90))
                .andExpect(jsonPath("$.lastRecordedAt").value("2026-01-01T09:58:40Z"))
                .andExpect(jsonPath("$.lastRecordedType").value("SUPERVISOR_HEARTBEAT"))
                .andExpect(jsonPath("$.board.lastEventId").value(42))
                .andExpect(jsonPath("$.board.equipment[?(@.id=='RAD-N')].health").value("FAILED"))
                .andExpect(jsonPath("$.board.equipment[?(@.id=='RAD-N')].sampledAt").value("2026-01-01T09:59:40Z"))
                .andExpect(jsonPath("$.board.equipment[?(@.id=='RAD-N')].values.plotsPerScan").value(300))
                .andExpect(jsonPath("$.board.equipment[?(@.id=='RAD-N')].contact.lastPollAt").value((Object) null))
                .andExpect(jsonPath("$.board.equipment[?(@.id=='RAD-N')].contact.consecutiveFailures").value(0))
                .andExpect(jsonPath("$.board.equipment[?(@.id=='RAD-E')].sampledAt").value((Object) null));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void eventsPageByAfterAndFlagMore() throws Exception {
        List<EventLogEntity> rows = List.of(
                row(11, new SupervisorEvent(EventType.SUPERVISOR_STARTED, T, null)),
                row(12, new TelemetrySampleEvent(T.plusSeconds(1), "RAD-N", T, Map.of("plotsPerScan", 300L), Map.of())),
                row(13, new CommandEvent(T.plusSeconds(2), "RAD-N", "RESTART", "supervisor1", "DONE", "c-1")));
        given(events.findPageAfter(eq(10L), any(Pageable.class))).willReturn(rows); // limit 2 asks for 3

        mvc.perform(get("/api/v1/history/events").param("after", "10").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.more").value(true))
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.events[0].id").value(11))
                .andExpect(jsonPath("$.events[0].type").value("SUPERVISOR_STARTED"))
                .andExpect(jsonPath("$.events[0].summary").value("Supervisor started"))
                .andExpect(jsonPath("$.events[0].message.kind").value("supervisor"))
                .andExpect(jsonPath("$.events[1].id").value(12))
                .andExpect(jsonPath("$.events[1].equipmentId").value("RAD-N"))
                .andExpect(jsonPath("$.events[1].message.kind").value("sample"))
                .andExpect(jsonPath("$.events[1].message.data.values.plotsPerScan").value(300));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void untilIsRespected() throws Exception {
        Instant until = T.plusSeconds(5);
        EventLogEntity issued = row(21, new CommandEvent(T, "RAD-N", "RESTART", "supervisor1", "ISSUED", "c-1"));
        given(events.findPageAfterUntil(eq(0L), eq(until), any(Pageable.class))).willReturn(List.of(issued));

        mvc.perform(get("/api/v1/history/events").param("until", "2026-01-01T10:00:05Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.more").value(false))
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].actor").value("supervisor1"))
                .andExpect(jsonPath("$.events[0].message.kind").value("command"))
                .andExpect(jsonPath("$.events[0].message.data.commandId").value("c-1"));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void timelineAndRaisedAreServed() throws Exception {
        Instant from = T;
        Instant to = T.plus(Duration.ofHours(1));
        given(timeline.timeline(from, to)).willReturn(new Timeline(
                List.of(new Timeline.Marker(T.plusSeconds(9), "RAD-N", 101, "CRITICAL", "Radar failure", false)), false,
                List.of(new Timeline.Span(T.plusSeconds(10), null)), List.of(), List.of(T)));
        EventLogEntity raised = org.mockito.Mockito.mock(EventLogEntity.class);
        given(raised.getOccurredAt()).willReturn(T.plusSeconds(3));
        given(events.findFirstByTypeAndEntityIdOrderByIdDesc("ALARM_RAISED", "a1")).willReturn(Optional.of(raised));
        given(events.findFirstByTypeAndEntityIdOrderByIdDesc("ALARM_RAISED", "nope")).willReturn(Optional.empty());

        mvc.perform(get("/api/v1/history/timeline").param("from", "2026-01-01T10:00:00Z")
                        .param("to", "2026-01-01T11:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markers[0].code").value(101))
                .andExpect(jsonPath("$.markers[0].suppressed").value(false))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.floods[0].end").value((Object) null))
                .andExpect(jsonPath("$.restarts[0]").value("2026-01-01T10:00:00Z"));
        mvc.perform(get("/api/v1/history/alarms/a1/raised"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occurredAt").value("2026-01-01T10:00:03Z"));
        mvc.perform(get("/api/v1/history/alarms/nope/raised")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void badParametersAre400() throws Exception {
        mvc.perform(get("/api/v1/history/board").param("at", "yesterday")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/history/board")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/history/events").param("limit", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/history/events").param("limit", "1001")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/history/events").param("after", "x")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/history/timeline").param("from", "2026-01-01T11:00:00Z")
                .param("to", "2026-01-01T11:00:00Z")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/history/timeline").param("from", "2026-01-01T12:00:00Z")
                .param("to", "2026-01-01T11:00:00Z")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/history/timeline").param("from", "2026-01-01T00:00:00Z")
                .param("to", "2026-01-08T00:00:01Z")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The timeline window is at most 7 days"));
        verifyNoInteractions(timeline);
        verifyNoInteractions(events);
    }

    @Test
    @WithMockUser(username = "supervisor1", roles = "VIEWER")
    void exportIsAnAttachmentWithTheDocumentedName() throws Exception {
        Instant from = T;
        Instant to = T.plus(Duration.ofMinutes(90));
        given(history.fold(from)).willReturn(Board.initial(inventory, List.of()));
        given(history.fold(to)).willReturn(Board.initial(inventory, List.of()));
        given(timeline.timeline(from, to)).willReturn(new Timeline(List.of(), false, List.of(), List.of(), List.of()));
        EventLogEntity raisedRow = row(5, new SupervisorEvent(EventType.SUPERVISOR_STARTED, T.plusSeconds(1), null));
        EventLogEntity commandRow = row(6, new CommandEvent(T.plusSeconds(2), "RAD-N", "RESTART", "supervisor1",
                "ISSUED", "c-1"));
        given(events.findByTypeNotAndOccurredAtBetweenOrderByIdAsc("TELEMETRY_SAMPLED", from, to))
                .willReturn(List.of(raisedRow, commandRow));

        mvc.perform(get("/api/v1/history/export").param("from", "2026-01-01T10:00:00Z")
                        .param("to", "2026-01-01T11:30:00Z").param("format", "json"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition",
                                "attachment; filename=\"incident-20260101T1000Z-20260101T1130Z.json\""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.centre").value(inventory.centre()))
                .andExpect(jsonPath("$.generatedBy").value("supervisor1"))
                .andExpect(jsonPath("$.generatedAt").value("2026-01-02T00:00:00Z"))
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.events[1].actor").value("supervisor1"))
                .andExpect(jsonPath("$.summary.commands[0].outcome").value("NOT_RECORDED"));

        mvc.perform(get("/api/v1/history/export").param("from", "2026-01-01T10:00:00Z")
                        .param("to", "2026-01-01T11:30:00Z").param("format", "csv"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition",
                                "attachment; filename=\"incident-20260101T1000Z-20260101T1130Z.csv\""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentTypeCompatibleWith("text/csv"));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void exportOverSevenDaysIs400() throws Exception {
        mvc.perform(get("/api/v1/history/export").param("from", "2026-01-01T00:00:00Z")
                        .param("to", "2026-01-08T00:00:01Z").param("format", "csv"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The export window is at most 7 days"));
        mvc.perform(get("/api/v1/history/export").param("from", "2026-01-01T11:00:00Z")
                .param("to", "2026-01-01T11:00:00Z").param("format", "csv")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/history/export").param("from", "2026-01-01T10:00:00Z")
                .param("to", "2026-01-01T11:00:00Z").param("format", "pdf")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/history/export").param("from", "2026-01-01T10:00:00Z")
                .param("to", "2026-01-01T11:00:00Z")).andExpect(status().isBadRequest());
        verifyNoInteractions(timeline);
        verifyNoInteractions(events);
    }

    @Test
    void anonymousIs401() throws Exception {
        mvc.perform(get("/api/v1/history/board").param("at", "2026-01-01T10:00:00Z"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/history/events")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/history/timeline").param("from", "2026-01-01T10:00:00Z")
                .param("to", "2026-01-01T11:00:00Z")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/history/alarms/a1/raised")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/history/export").param("from", "2026-01-01T10:00:00Z")
                .param("to", "2026-01-01T11:00:00Z").param("format", "csv")).andExpect(status().isUnauthorized());
        verifyNoInteractions(history, events, timeline);
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void aViewerMayReplay() throws Exception {
        given(history.boardAt(T)).willReturn(new ReplayBoard(T, Board.initial(inventory, List.of()), Map.of(), true, null, null));
        mvc.perform(get("/api/v1/history/board").param("at", "2026-01-01T10:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.noData").value(true));
    }
}
