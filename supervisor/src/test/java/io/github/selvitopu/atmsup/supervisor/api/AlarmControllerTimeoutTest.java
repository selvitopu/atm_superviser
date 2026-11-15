package io.github.selvitopu.atmsup.supervisor.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.supervisor.CapturedLogs;
import io.github.selvitopu.atmsup.supervisor.board.BoardResult;
import io.github.selvitopu.atmsup.supervisor.board.BoardWriter;
import io.github.selvitopu.atmsup.supervisor.history.EventQueries;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import io.github.selvitopu.atmsup.supervisor.security.SecurityConfig;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = AlarmController.class, properties = "atm.supervisor.api.submit-timeout=200ms")
@Import(SecurityConfig.class)
@WithMockUser(username = "supervisor1", roles = "SUPERVISOR")
class AlarmControllerTimeoutTest {

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private BoardWriter writer;
    @MockitoBean
    private EventQueries queries;
    @MockitoBean
    private DtoMapper mapper;
    @MockitoBean
    private Inventory inventory;

    @Test
    void aWriterThatNeverAnswersGives503AndLeavesItsFutureAlone() throws Exception {
        var never = new CompletableFuture<BoardResult>();
        given(writer.submit(any())).willReturn(never);

        try (CapturedLogs logs = new CapturedLogs(AlarmController.class)) {
            mvc.perform(post("/api/v1/alarms/a1/ack").with(csrf()).contentType(APPLICATION_JSON).content("{}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.title").value("Board update failed"))
                    .andExpect(jsonPath("$.detail").value("The board did not answer in time"));
            assertThat(logs.warnings()).hasSize(1);
        }
        assertThat(never).isNotDone();
    }
}
