package io.github.selvitopu.atmsup.supervisor.simconsole;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.selvitopu.atmsup.supervisor.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/** Outside demo mode there is no simulator to drive, so the console does not exist at all. */
@WebMvcTest(controllers = SimulatorConsoleController.class, properties = "atm.demo-mode=false")
@Import(SecurityConfig.class)
class SimulatorConsoleAbsentTest {

    @Autowired
    MockMvc mvc;

    @Test
    @WithMockUser(username = "admin1", roles = "ADMIN")
    void theConsoleIsAbsentOutsideDemoMode() throws Exception {
        mvc.perform(get("/api/v1/simulator/devices")).andExpect(status().isNotFound());
    }
}
