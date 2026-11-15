package io.github.selvitopu.atmsup.supervisor.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import io.github.selvitopu.atmsup.supervisor.api.AlarmController;
import io.github.selvitopu.atmsup.supervisor.api.BoardController;
import io.github.selvitopu.atmsup.supervisor.api.DtoMapper;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Who may call what, with the real security configuration in front of the real controllers. */
@WebMvcTest({AlarmController.class, BoardController.class, AuthController.class})
@Import({SecurityConfig.class, DtoMapper.class, SecurityRulesTest.Inventories.class})
class SecurityRulesTest {

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
    MockMvc mvc;
    @MockitoBean
    BoardWriter writer;
    @MockitoBean
    EventQueries queries;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    AccountService accounts;

    @BeforeEach
    void liveBoard() {
        var eq = Map.of("RAD-N", new EquipmentState("RAD-N", Health.OPERATIONAL, null, false, Role.STANDALONE, T,
                new Contact(true, T, null)));
        given(writer.current())
                .willReturn(new LiveBoard(new Board(eq, Map.of(), Map.of(), 1), Map.of(), Map.of(), Map.of()));
        Alarm acked = new Alarm("a1", new AlarmKey("RAD-N", 401, 1), "", Severity.MINOR, "Sensor input lost",
                AlarmSource.DEVICE, AlarmState.ACTIVE_ACK, false, T, T, "supervisor1", null, null);
        given(writer.submit(any())).willReturn(CompletableFuture.completedFuture(
                new BoardResult(true, List.of(acked), List.of(), null)));
    }

    @Test
    void anonymousApiCallsGet401ProblemDetails() throws Exception {
        mvc.perform(get("/api/v1/board"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Sign in to continue"))
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    @WithMockUser(username = "demo1", roles = "VIEWER")
    void aViewerCannotAcknowledge() throws Exception {
        mvc.perform(post("/api/v1/alarms/a1/ack").with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Your role does not allow this"));
    }

    @Test
    @WithMockUser(username = "supervisor1", roles = "SUPERVISOR")
    void aSupervisorAcknowledgesAsThemself() throws Exception {
        mvc.perform(post("/api/v1/alarms/a1/ack").with(csrf())).andExpect(status().isOk());

        ArgumentCaptor<BoardInput> in = ArgumentCaptor.forClass(BoardInput.class);
        verify(writer).submit(in.capture());
        assertThat(in.getValue()).isEqualTo(new AcknowledgeAlarms(List.of("a1"), null, "supervisor1"));
    }

    @Test
    @WithMockUser(username = "admin1", roles = "ADMIN")
    void anAdminMayAcknowledgeThroughTheHierarchy() throws Exception {
        mvc.perform(post("/api/v1/alarms/a1/ack").with(csrf())).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "supervisor1", roles = "SUPERVISOR")
    void aPostWithoutCsrfIsRefused() throws Exception {
        mvc.perform(post("/api/v1/alarms/a1/ack")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "supervisor1", roles = "SUPERVISOR")
    void meReturnsTheSignedInUser() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("supervisor1"))
                .andExpect(jsonPath("$.roles[0]").value("SUPERVISOR"));
    }

    @Test
    void actuatorAndDocsStayOpen() throws Exception {
        // permitted, so not 401; springdoc itself is not part of this web slice
        mvc.perform(get("/api/v1/openapi.json")).andExpect(status().isNotFound());
    }
}
