package io.github.selvitopu.atmsup.supervisor.simconsole;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.selvitopu.atmsup.supervisor.security.SecurityConfig;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

@WebMvcTest(controllers = SimulatorConsoleController.class, properties = {
        "atm.demo-mode=true", "atm.simulator.console-url=http://simulator.test:8090"})
@Import({SecurityConfig.class, SimulatorConsoleControllerTest.Simulator.class})
class SimulatorConsoleControllerTest {

    private static final String DEVICE = "{\"id\":\"RAD-N\",\"status\":\"FAILED\",\"faults\":[\"FAIL\"]}";

    /** Binds the mock server to the builder before the controller builds its client from it. */
    @TestConfiguration
    static class Simulator {
        static MockRestServiceServer server;

        @Bean
        RestClient.Builder restClientBuilder() {
            RestClient.Builder builder = RestClient.builder();
            server = MockRestServiceServer.bindTo(builder).build();
            return builder;
        }
    }

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void reset() {
        Simulator.server.reset();
    }

    @AfterEach
    void verify() {
        Simulator.server.verify();
    }

    @Test
    @WithMockUser(username = "supervisor1", roles = "SUPERVISOR")
    void forwardsTheDeviceList() throws Exception {
        Simulator.server.expect(requestTo("http://simulator.test:8090/api/devices")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[" + DEVICE + "]", APPLICATION_JSON));

        mvc.perform(get("/api/v1/simulator/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("RAD-N"));
    }

    @Test
    @WithMockUser(username = "supervisor1", roles = "SUPERVISOR")
    void forwardsAFaultAndReturnsTheDevice() throws Exception {
        Simulator.server.expect(requestTo("http://simulator.test:8090/api/devices/RAD-N/faults"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"fault\":\"FAIL\",\"target\":null}"))
                .andRespond(withSuccess(DEVICE, APPLICATION_JSON));

        mvc.perform(post("/api/v1/simulator/devices/RAD-N/faults").with(csrf()).contentType(APPLICATION_JSON)
                        .content("{\"fault\":\"FAIL\",\"target\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    @WithMockUser(username = "supervisor1", roles = "SUPERVISOR")
    void resetsTheSimulator() throws Exception {
        Simulator.server.expect(requestTo("http://simulator.test:8090/api/reset")).andExpect(method(HttpMethod.POST))
                .andRespond(withNoContent());

        mvc.perform(post("/api/v1/simulator/reset").with(csrf())).andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(username = "demo1", roles = "VIEWER")
    void aViewerCannotInjectFaults() throws Exception {
        mvc.perform(post("/api/v1/simulator/reset").with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Your role does not allow this"));
    }

    @Test
    @WithMockUser(username = "supervisor1", roles = "SUPERVISOR")
    void aSimulatorRefusalPassesThroughWithItsDetail() throws Exception {
        Simulator.server.expect(requestTo("http://simulator.test:8090/api/devices/RAD-N/faults"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"status\":400,\"detail\":\"Fault SENSOR_LOST needs a target\"}"));

        mvc.perform(post("/api/v1/simulator/devices/RAD-N/faults").with(csrf()).contentType(APPLICATION_JSON)
                        .content("{\"fault\":\"SENSOR_LOST\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Fault SENSOR_LOST needs a target"));
    }

    @Test
    @WithMockUser(username = "supervisor1", roles = "SUPERVISOR")
    void anUnreachableSimulatorIs502() throws Exception {
        Simulator.server.expect(requestTo("http://simulator.test:8090/api/devices"))
                .andRespond(withException(new IOException("Connection refused")));

        mvc.perform(get("/api/v1/simulator/devices"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("The simulator does not answer"));
    }
}
