package io.github.selvitopu.atmsup.supervisor.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.supervisor.board.Board;
import io.github.selvitopu.atmsup.supervisor.board.BoardWriter;
import io.github.selvitopu.atmsup.supervisor.board.LiveBoard;
import io.github.selvitopu.atmsup.supervisor.board.ServiceId;
import io.github.selvitopu.atmsup.supervisor.board.ServiceStatus;
import io.github.selvitopu.atmsup.supervisor.impact.ImpactAnalyzer;
import io.github.selvitopu.atmsup.supervisor.impact.ImpactReport;
import io.github.selvitopu.atmsup.supervisor.impact.PlanResult;
import io.github.selvitopu.atmsup.supervisor.impact.ServiceChange;
import io.github.selvitopu.atmsup.supervisor.impact.Verdict;
import io.github.selvitopu.atmsup.supervisor.security.SecurityConfig;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ImpactController.class)
@Import({ImpactControllerTest.Beans.class, SecurityConfig.class})
class ImpactControllerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final String BASIS = "Derived from the supervised equipment and the service rules; not a safety"
            + " assessment";

    @TestConfiguration
    static class Beans {
        @Bean
        InstantSource clock() {
            return InstantSource.fixed(NOW);
        }
    }

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private BoardWriter writer;
    @MockitoBean
    private Inventory inventory;
    @MockitoBean
    private ImpactAnalyzer analyzer;

    private final Board board = new Board(Map.of(), Map.of(), Map.of(), 7);

    @BeforeEach
    void liveBoard() {
        given(inventory.find("FDPS-B")).willReturn(Optional.of(mock(Device.class)));
        given(inventory.find("NOPE")).willReturn(Optional.empty());
        given(writer.current()).willReturn(new LiveBoard(board, Map.of(), Map.of(), Map.of()));
        ServiceChange change = new ServiceChange(ServiceId.FD, Verdict.REDUCED_MARGIN, ServiceStatus.OK, null,
                ServiceStatus.DEGRADED, null, true, List.of("FDPS-B is in maintenance"));
        given(analyzer.analyze(any(), any())).willReturn(new ImpactReport(7,
                new PlanResult(List.of("FDPS-B"), Verdict.REDUCED_MARGIN, false, List.of(change), List.of()),
                List.of(), List.of(), List.of()));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void aViewerGetsTheReport() throws Exception {
        mvc.perform(get("/api/v1/impact").param("maintenance", "FDPS-B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basis").value(BASIS))
                .andExpect(jsonPath("$.asOf").value("2026-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.plan.verdict").value("REDUCED_MARGIN"))
                .andExpect(jsonPath("$.plan.services[0].id").value("FD"))
                .andExpect(jsonPath("$.plan.services[0].name").value("Flight data"))
                .andExpect(jsonPath("$.plan.services[0].after").value("DEGRADED"))
                .andExpect(jsonPath("$.plan.services[0].afterBestCase").isEmpty())
                .andExpect(jsonPath("$.plan.services[0].redundancyLostAfter").value(true));
    }

    @Test
    void signedOutIs401() throws Exception {
        mvc.perform(get("/api/v1/impact")).andExpect(status().isUnauthorized());
        verifyNoInteractions(analyzer);
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void anUnknownUnitIs400() throws Exception {
        mvc.perform(get("/api/v1/impact").param("maintenance", "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown unit: NOPE"));
        verifyNoInteractions(analyzer);
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void duplicatesAreIgnored() throws Exception {
        mvc.perform(get("/api/v1/impact").param("maintenance", "FDPS-B", "FDPS-B")).andExpect(status().isOk());
        verify(analyzer).analyze(board, Set.of("FDPS-B"));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void noPlanIsAnEmptySet() throws Exception {
        mvc.perform(get("/api/v1/impact")).andExpect(status().isOk());
        verify(analyzer).analyze(board, Set.of());
    }
}
