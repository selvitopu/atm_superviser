package io.github.selvitopu.atmsup.supervisor.api;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.selvitopu.atmsup.supervisor.control.CommandOutcome;
import io.github.selvitopu.atmsup.supervisor.control.EquipmentCommand;
import io.github.selvitopu.atmsup.supervisor.control.EquipmentControl;
import io.github.selvitopu.atmsup.supervisor.security.SecurityConfig;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(CommandController.class)
@Import(SecurityConfig.class)
class CommandControllerTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    EquipmentControl control;

    private ResultActions command(String id, String body) throws Exception {
        return mvc.perform(post("/api/v1/equipment/" + id + "/commands").with(csrf())
                .contentType(APPLICATION_JSON).content(body));
    }

    private void outcome(CommandOutcome outcome) {
        given(control.command("FDPS-B", EquipmentCommand.MAINTENANCE_ON, "supervisor1")).willReturn(outcome);
    }

    @Test
    @WithMockUser(username = "demo1", roles = "VIEWER")
    void aViewerCannotCommand() throws Exception {
        command("FDPS-B", "{\"command\":\"MAINTENANCE_ON\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Your role does not allow this"));
        verifyNoInteractions(control);
    }

    @Test
    @WithMockUser(username = "supervisor1", roles = "SUPERVISOR")
    void aSupervisorCommandsAsThemself() throws Exception {
        outcome(CommandOutcome.DONE);

        command("FDPS-B", "{\"command\":\"MAINTENANCE_ON\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.equipmentId").value("FDPS-B"))
                .andExpect(jsonPath("$.command").value("MAINTENANCE_ON"))
                .andExpect(jsonPath("$.outcome").value("DONE"));
    }

    @Test
    @WithMockUser(username = "supervisor1", roles = "SUPERVISOR")
    void aRefusalIs409WithTheRule() throws Exception {
        outcome(CommandOutcome.REFUSED);

        command("FDPS-B", "{\"command\":\"MAINTENANCE_ON\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The equipment refused the command: restart needs maintenance"
                        + " mode, and make active needs a standby unit"));
    }

    @Test
    @WithMockUser(username = "supervisor1", roles = "SUPERVISOR")
    void noAnswerIs504() throws Exception {
        outcome(CommandOutcome.NO_ANSWER);

        command("FDPS-B", "{\"command\":\"MAINTENANCE_ON\"}")
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.detail").value("No answer from the equipment: the command may or may not have"
                        + " been applied. Check the unit before you send it again"));
    }

    @Test
    @WithMockUser(username = "supervisor1", roles = "SUPERVISOR")
    void aFailureToSendIs502() throws Exception {
        outcome(CommandOutcome.FAILED);

        command("FDPS-B", "{\"command\":\"MAINTENANCE_ON\"}")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("The command could not be sent to the equipment; check the"
                        + " operator SNMP credentials"));
    }

    @Test
    @WithMockUser(username = "supervisor1", roles = "SUPERVISOR")
    void unknownEquipmentIs404() throws Exception {
        given(control.command("NOPE", EquipmentCommand.RESTART, "supervisor1"))
                .willThrow(new NoSuchElementException("Unknown equipment NOPE"));

        command("NOPE", "{\"command\":\"RESTART\"}").andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "supervisor1", roles = "SUPERVISOR")
    void anUnknownOrMissingCommandIs400() throws Exception {
        command("FDPS-B", "{\"command\":\"SELF_DESTRUCT\"}").andExpect(status().isBadRequest());
        command("FDPS-B", "{}").andExpect(status().isBadRequest());
        verifyNoInteractions(control);
    }
}
