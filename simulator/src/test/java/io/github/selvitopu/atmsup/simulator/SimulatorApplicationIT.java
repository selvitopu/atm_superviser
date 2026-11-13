package io.github.selvitopu.atmsup.simulator;

import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_STATUS;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.scalar;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.selvitopu.atmsup.simulator.fixture.FreePorts;
import io.github.selvitopu.atmsup.simulator.fixture.TestInventories;
import io.github.selvitopu.atmsup.simulator.fixture.TestManager;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.snmp4j.PDU;
import org.snmp4j.smi.Integer32;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SimulatorApplicationIT {

    private static final int FIRST_PORT = FreePorts.udpBlock(7);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        TestInventories.asProperties(TestInventories.v01(FIRST_PORT, FreePorts.udp(1)[0])).forEach((k, v) -> registry.add(k, () -> v));
        registry.add("atm.snmp.monitor.auth-password", () -> "monitor-auth-demo");
        registry.add("atm.snmp.monitor.priv-password", () -> "monitor-priv-demo");
        registry.add("atm.snmp.operator.auth-password", () -> "operator-auth-demo");
        registry.add("atm.snmp.operator.priv-password", () -> "operator-priv-demo");
        String dataDir = Files.createTempDirectory("atm-sim-it").toString();
        registry.add("atm.simulator.data-dir", () -> dataDir);
        registry.add("atm.simulator.bind-host", () -> "127.0.0.1");
    }

    @LocalServerPort
    int httpPort;

    private Integer32 radarNorthStatus() {
        try (TestManager m = new TestManager("127.0.0.1", FIRST_PORT, "monitor", "monitor-auth-demo",
                "monitor-priv-demo")) {
            PDU p = m.get(scalar(EQ_STATUS));
            return p == null ? null : (Integer32) p.getVariable(scalar(EQ_STATUS));
        }
    }

    @Test
    void agentsServeAndFaultsShow() throws Exception {
        assertThat(radarNorthStatus()).isEqualTo(new Integer32(1));

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + httpPort + "/api/devices/RAD-N/faults"))
                        .header("Content-Type", "application/json")
                        .POST(BodyPublishers.ofString("{\"fault\":\"FAIL\"}")).build(),
                BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        await().atMost(ofSeconds(2)).untilAsserted(() -> assertThat(radarNorthStatus()).isEqualTo(new Integer32(3)));
    }
}
