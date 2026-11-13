package io.github.selvitopu.atmsup.simulator.api;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.simulator.centre.Centre;
import io.github.selvitopu.atmsup.simulator.fixture.TestInventories;
import java.time.InstantSource;
import java.util.SplittableRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SimulatorController.class)
@Import(SimulatorControllerTest.CentreConfig.class)
class SimulatorControllerTest {

    @TestConfiguration
    static class CentreConfig {
        @Bean
        Centre centre() {
            return new Centre(Inventory.from(TestInventories.v01(20000, 10162)), InstantSource.system(),
                    new SplittableRandom(7));
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    Centre centre;

    @BeforeEach
    void nominal() {
        centre.reset(); // the context, and so the centre, is shared by the tests
    }

    @Test
    void listsAllDevices() throws Exception {
        mvc.perform(get("/api/devices")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[0].id").value("RAD-N")).andExpect(jsonPath("$[0].status").value("OPERATIONAL"));
    }

    @Test
    void getsOneDevice() throws Exception {
        mvc.perform(get("/api/devices/SDPS-A")).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ACTIVE")).andExpect(jsonPath("$.responding").value(true))
                .andExpect(jsonPath("$.values.trackCount").isNumber());
    }

    @Test
    void postFaultReturnsDeviceState() throws Exception {
        mvc.perform(post("/api/devices/RAD-N/faults").contentType(APPLICATION_JSON).content("{\"fault\":\"FAIL\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.faults[0]").value("FAIL"));
    }

    @Test
    void inapplicableFaultIs400() throws Exception {
        mvc.perform(post("/api/devices/RAD-E/faults").contentType(APPLICATION_JSON)
                        .content("{\"fault\":\"SENSOR_LOST\",\"target\":\"RAD-N\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Fault SENSOR_LOST not applicable to RADAR"));
    }

    @Test
    void missingTargetIs400() throws Exception {
        mvc.perform(post("/api/devices/SDPS-A/faults").contentType(APPLICATION_JSON)
                        .content("{\"fault\":\"SENSOR_LOST\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Fault SENSOR_LOST needs a target"));
    }

    @Test
    void unknownDeviceIs404() throws Exception {
        mvc.perform(post("/api/devices/X-1/faults").contentType(APPLICATION_JSON).content("{\"fault\":\"FAIL\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("Unknown equipment X-1"));
        mvc.perform(get("/api/devices/X-1")).andExpect(status().isNotFound());
    }

    @Test
    void resetIs204AndRestoresNominal() throws Exception {
        mvc.perform(post("/api/devices/RAD-S/faults").contentType(APPLICATION_JSON).content("{\"fault\":\"FAIL\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/reset")).andExpect(status().isNoContent());

        mvc.perform(get("/api/devices/RAD-S")).andExpect(jsonPath("$.status").value("OPERATIONAL"))
                .andExpect(jsonPath("$.faults.length()").value(0));
    }
}
