package io.github.selvitopu.atmsup.supervisor.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import io.github.selvitopu.atmsup.supervisor.CapturedLogs;
import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmKey;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmSource;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmState;
import io.github.selvitopu.atmsup.supervisor.board.Board;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.AcknowledgeAlarms;
import io.github.selvitopu.atmsup.supervisor.board.BoardResult;
import io.github.selvitopu.atmsup.supervisor.board.BoardWriter;
import io.github.selvitopu.atmsup.supervisor.board.Contact;
import io.github.selvitopu.atmsup.supervisor.board.EquipmentState;
import io.github.selvitopu.atmsup.supervisor.board.Health;
import io.github.selvitopu.atmsup.supervisor.board.LiveBoard;
import io.github.selvitopu.atmsup.supervisor.history.EventQueries;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import io.github.selvitopu.atmsup.supervisor.security.SecurityConfig;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AlarmController.class)
@Import({DtoMapper.class, AlarmControllerTest.Inventories.class, SecurityConfig.class})
@WithMockUser(username = "supervisor1", roles = "SUPERVISOR")
class AlarmControllerTest {

    private static final Instant T = Instant.parse("2026-01-01T10:00:00Z");

    @TestConfiguration
    static class Inventories {
        @Bean
        Inventory inventory() {
            return Inventory.from(new InventoryProperties("Demo ACC", "127.0.0.1", Duration.ofSeconds(5),
                    new TrapTarget("127.0.0.1", 10162),
                    List.of(new DeviceProperties("RAD-N", DeviceType.RADAR, "North radar", Group.SURVEILLANCE, null,
                            16101, null, null))));
        }
    }

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private BoardWriter writer;
    @MockitoBean
    private EventQueries queries;

    @BeforeEach
    void liveBoard() {
        var eq = Map.of("RAD-N", new EquipmentState("RAD-N", Health.OPERATIONAL, null, false, Role.STANDALONE, T,
                new Contact(true, T, null)));
        given(writer.current())
                .willReturn(new LiveBoard(new Board(eq, Map.of(), Map.of(), 1), Map.of(), Map.of(), Map.of()));
    }

    private static Alarm ackedAlarm(String id) {
        return new Alarm(id, new AlarmKey("RAD-N", 401, 1), "", Severity.MINOR, "Sensor input lost",
                AlarmSource.DEVICE, AlarmState.ACTIVE_ACK, false, T, T.plusSeconds(5), "supervisor1", "seen", null);
    }

    @Test
    void ackReturnsAlarmAndSubmitsTheSignedInActor() throws Exception {
        given(writer.submit(any())).willReturn(CompletableFuture.completedFuture(
                new BoardResult(true, List.of(ackedAlarm("a1")), List.of(), null)));

        mvc.perform(post("/api/v1/alarms/a1/ack").with(csrf()).contentType(APPLICATION_JSON).content("{\"comment\":\"seen\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("a1"))
                .andExpect(jsonPath("$.state").value("ACTIVE_ACK"))
                .andExpect(jsonPath("$.ackedBy").value("supervisor1"))
                .andExpect(jsonPath("$.ackComment").value("seen"));

        ArgumentCaptor<BoardInput> in = ArgumentCaptor.forClass(BoardInput.class);
        verify(writer).submit(in.capture());
        assertThat(in.getValue()).isEqualTo(new AcknowledgeAlarms(List.of("a1"), "seen", "supervisor1"));
    }

    @Test
    void ackWithoutBodyIsAllowed() throws Exception {
        given(writer.submit(any())).willReturn(CompletableFuture.completedFuture(
                new BoardResult(true, List.of(ackedAlarm("a1")), List.of(), null)));
        mvc.perform(post("/api/v1/alarms/a1/ack").with(csrf())).andExpect(status().isOk());
        ArgumentCaptor<BoardInput> in = ArgumentCaptor.forClass(BoardInput.class);
        verify(writer).submit(in.capture());
        assertThat(in.getValue()).isEqualTo(new AcknowledgeAlarms(List.of("a1"), null, "supervisor1"));
    }

    @Test
    void ackUnknownIs404() throws Exception {
        given(writer.submit(any())).willReturn(CompletableFuture.completedFuture(
                new BoardResult(true, List.of(), List.of("a9"), null)));
        mvc.perform(post("/api/v1/alarms/a9/ack").with(csrf()).contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not found"))
                .andExpect(jsonPath("$.detail").value("Unknown alarm a9"));
    }

    @Test
    void failedCommitIs503() throws Exception {
        given(writer.submit(any())).willReturn(CompletableFuture.completedFuture(BoardResult.failed("db down")));
        mvc.perform(post("/api/v1/alarms/a1/ack").with(csrf()).contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Board update failed"));
        mvc.perform(post("/api/v1/alarms/ack").with(csrf()).contentType(APPLICATION_JSON).content("{\"ids\":[\"a1\"]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Board update failed"));
    }

    @Test
    void everySubmitFailureIs503AndIsLogged() throws Exception {
        var failed = new CompletableFuture<BoardResult>();
        failed.completeExceptionally(new IllegalStateException("writer exploded"));
        var cancelled = new CompletableFuture<BoardResult>();
        cancelled.cancel(true);
        given(writer.submit(any())).willReturn(failed).willReturn(cancelled)
                .willThrow(new java.util.concurrent.RejectedExecutionException("queue full"));

        try (CapturedLogs logs = new CapturedLogs(AlarmController.class)) {
            for (int i = 0; i < 3; i++) {
                mvc.perform(post("/api/v1/alarms/a1/ack").with(csrf()).contentType(APPLICATION_JSON).content("{}"))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.title").value("Board update failed"));
            }
            assertThat(logs.events()).hasSize(3);
            assertThat(logs.events().get(0).getThrowableProxy().getMessage()).isEqualTo("writer exploded");
            assertThat(logs.events().get(2).getThrowableProxy().getMessage()).isEqualTo("queue full");
        }
    }

    @Test
    void ackManyReturnsAcknowledgedAndNotFound() throws Exception {
        given(writer.submit(any())).willReturn(CompletableFuture.completedFuture(
                new BoardResult(true, List.of(ackedAlarm("a1")), List.of("a9"), null)));
        mvc.perform(post("/api/v1/alarms/ack").with(csrf()).contentType(APPLICATION_JSON)
                        .content("{\"ids\":[\"a1\",\"a9\"],\"comment\":\"seen\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledged[0].id").value("a1"))
                .andExpect(jsonPath("$.notFound[0]").value("a9"));
        ArgumentCaptor<BoardInput> in = ArgumentCaptor.forClass(BoardInput.class);
        verify(writer).submit(in.capture());
        assertThat(in.getValue()).isEqualTo(new AcknowledgeAlarms(List.of("a1", "a9"), "seen", "supervisor1"));
    }

    @Test
    void invalidAckBodiesAre400() throws Exception {
        mvc.perform(post("/api/v1/alarms/ack").with(csrf()).contentType(APPLICATION_JSON).content("{\"ids\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/alarms/ack").with(csrf()).contentType(APPLICATION_JSON).content("{\"comment\":\"x\"}"))
                .andExpect(status().isBadRequest());
        String ids = "[" + String.join(",", Collections.nCopies(501, "\"a\"")) + "]";
        mvc.perform(post("/api/v1/alarms/ack").with(csrf()).contentType(APPLICATION_JSON).content("{\"ids\":" + ids + "}"))
                .andExpect(status().isBadRequest());
        String longComment = "x".repeat(501);
        mvc.perform(post("/api/v1/alarms/a1/ack").with(csrf()).contentType(APPLICATION_JSON)
                        .content("{\"comment\":\"" + longComment + "\"}"))
                .andExpect(status().isBadRequest());
        verify(writer, never()).submit(any());
    }

    @Test
    void searchPassesFiltersAndMapsThePage() throws Exception {
        given(queries.searchAlarms(any(), any(), any(), any(), any(), any())).willReturn(
                new PageImpl<>(List.of(ackedAlarm("a1")), PageRequest.of(2, 10), 31));

        mvc.perform(get("/api/v1/alarms?state=all&severity=MINOR&equipment=RAD-N&from=2026-01-01T00:00:00Z"
                        + "&to=2026-01-02T00:00:00Z&page=2&size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("a1"))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.total").value(31));

        ArgumentCaptor<Pageable> p = ArgumentCaptor.forClass(Pageable.class);
        verify(queries).searchAlarms(eq("all"), eq(Severity.MINOR), eq("RAD-N"),
                eq(Instant.parse("2026-01-01T00:00:00Z")), eq(Instant.parse("2026-01-02T00:00:00Z")), p.capture());
        assertThat(p.getValue()).isEqualTo(PageRequest.of(2, 10));
    }

    @Test
    void searchDefaultsToOpenAlarmsFirstPageOfFifty() throws Exception {
        given(queries.searchAlarms(any(), any(), any(), any(), any(), any())).willReturn(
                new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));
        mvc.perform(get("/api/v1/alarms")).andExpect(status().isOk()).andExpect(jsonPath("$.size").value(50));
        ArgumentCaptor<Pageable> p = ArgumentCaptor.forClass(Pageable.class);
        verify(queries).searchAlarms(eq("open"), isNull(), isNull(), isNull(), isNull(), p.capture());
        assertThat(p.getValue()).isEqualTo(PageRequest.of(0, 50));
    }

    @Test
    void oversizedPageIs400() throws Exception {
        mvc.perform(get("/api/v1/alarms?size=501")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/alarms?size=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/alarms?page=-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/alarms?state=bogus")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/alarms?severity=BOGUS")).andExpect(status().isBadRequest());
    }
}
