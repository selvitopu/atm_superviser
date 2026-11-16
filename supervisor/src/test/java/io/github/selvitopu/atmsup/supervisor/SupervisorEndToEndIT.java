package io.github.selvitopu.atmsup.supervisor;

import static io.github.selvitopu.atmsup.simulator.model.Fault.DROP_TRAPS;
import static io.github.selvitopu.atmsup.simulator.model.Fault.FAIL;
import static io.github.selvitopu.atmsup.simulator.model.Fault.RECOVER;
import static io.github.selvitopu.atmsup.simulator.model.Fault.UNREACHABLE;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.inventory.InventoryProperties;
import io.github.selvitopu.atmsup.inventory.SnmpProperties;
import io.github.selvitopu.atmsup.simulator.fixture.FreePorts;
import io.github.selvitopu.atmsup.simulator.fixture.SimulatorFixture;
import io.github.selvitopu.atmsup.simulator.fixture.TestInventories;
import io.github.selvitopu.atmsup.simulator.model.FaultRequest;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.AlarmDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.BoardDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.EquipmentDetailDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.EquipmentDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.EventSummaryDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.ServiceDto;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole supervisor against a real simulator and PostgreSQL: polling, traps, alarms, services, acknowledgement over
 * REST, the SSE stream, lost contact and recovery. The tests share one context and run in order.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "atm.demo-mode=true",
        "management.server.port=0",
        // expected start-up warnings stay off the console: the demo credentials, and Springdoc's reminder that the
        // OpenAPI document and Swagger UI are served (which this API does on purpose)
        "logging.level.io.github.selvitopu.atmsup.supervisor.SupervisorConfig=ERROR",
        "logging.level.org.springdoc.core.events.SpringDocAppInitializer=ERROR"})
@DirtiesContext // close the supervisor (and the simulator bean) after this class, while PostgreSQL still runs
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SupervisorEndToEndIT {

    /**
     * Started here and left to Testcontainers' reaper: stopping it in this class would happen before the context
     * closes, and the board writer still commits SUPERVISOR_STOPPING on shutdown.
     */
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    static {
        postgres.start();
    }

    private static final int TRAP_PORT = FreePorts.udp(1)[0];
    private static final InventoryProperties INVENTORY = TestInventories.v01(FreePorts.udpBlock(7), TRAP_PORT);
    /** One cookie jar for the REST client and the SSE recorders: the session and the CSRF token live there. */
    private static final CookieManager COOKIES = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private static final HttpClient http = HttpClient.newBuilder().cookieHandler(COOKIES).build();

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestInventories.asProperties(INVENTORY).forEach((k, v) -> registry.add(k, () -> v));
        SnmpProperties creds = TestInventories.demoCredentials();
        registry.add("atm.snmp.monitor.auth-password", () -> creds.monitor().authPassword());
        registry.add("atm.snmp.monitor.priv-password", () -> creds.monitor().privPassword());
        registry.add("atm.snmp.operator.auth-password", () -> creds.operator().authPassword());
        registry.add("atm.snmp.operator.priv-password", () -> creds.operator().privPassword());
        registry.add("atm.supervisor.trap-port", () -> TRAP_PORT);
        registry.add("atm.supervisor.data-dir", () -> dataDir.resolve("supervisor").toString());
    }

    /** The simulator lives with the context: it starts before polling begins and stops after polling has ended. */
    @TestConfiguration
    static class Simulator {
        @Bean(destroyMethod = "close")
        SimulatorFixture simulatorFixture() {
            return SimulatorFixture.start(Inventory.from(INVENTORY), TestInventories.demoCredentials(),
                    dataDir.resolve("simulator"));
        }
    }

    @AfterAll
    static void closeHttpClient() {
        http.close();
    }

    @LocalServerPort
    int port;
    @LocalManagementPort
    int managementPort;
    @Autowired
    SimulatorFixture fixture;
    @Autowired
    JsonMapper json;
    @Autowired
    JdbcTemplate jdbc;

    // --- REST helpers -------------------------------------------------------------------------------------------

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private static HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static HttpResponse<String> get(String url) {
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(ofSeconds(10)).build());
    }

    private <T> T getJson(String path, Class<T> type) {
        HttpResponse<String> response = get(baseUrl() + path);
        assertThat(response.statusCode()).as("GET %s", path).isEqualTo(200);
        return json.readValue(response.body(), type);
    }

    private static String csrfToken() {
        return COOKIES.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("XSRF-TOKEN"))
                .map(HttpCookie::getValue).findFirst().orElse("");
    }

    /** Signs in through the real form-login endpoint: first the sign-in configuration (which sets the CSRF cookie). */
    private HttpResponse<String> signIn(String username, String password) {
        get(baseUrl() + "/api/v1/auth/config");
        return send(HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/auth/login")).timeout(ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-XSRF-TOKEN", csrfToken())
                .POST(BodyPublishers.ofString("username=" + username + "&password=" + password))
                .build());
    }

    @BeforeEach
    void signedInAsSupervisor() {
        boolean signedIn = COOKIES.getCookieStore().getCookies().stream().anyMatch(c -> c.getName().equals("JSESSIONID"));
        if (!signedIn) {
            assertThat(signIn("supervisor1", "supervisor1-demo").statusCode()).isEqualTo(200);
        }
    }

    private BoardDto board() {
        return getJson("/api/v1/board", BoardDto.class);
    }

    private EquipmentDto eq(String id) {
        return board().equipment().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
    }

    private ServiceDto service(String id) {
        return board().services().stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }

    /** An alarm on the board (open, or cleared but not yet acknowledged). */
    private AlarmDto alarm(String equipmentId, int code) {
        return board().alarms().stream().filter(a -> a.equipmentId().equals(equipmentId) && a.code() == code)
                .findFirst().orElse(null);
    }

    /** An alarm whose condition is still active. */
    private AlarmDto openAlarm(String equipmentId, int code) {
        return board().alarms().stream().filter(a -> a.equipmentId().equals(equipmentId) && a.code() == code)
                .filter(a -> a.state().startsWith("ACTIVE")).findFirst().orElse(null);
    }

    private AlarmDto postAck(String alarmId, String comment) {
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                        URI.create(baseUrl() + "/api/v1/alarms/" + alarmId + "/ack"))
                .timeout(ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrfToken())
                .POST(BodyPublishers.ofString(json.writeValueAsString(Map.of("comment", comment))))
                .build());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readValue(response.body(), AlarmDto.class);
    }

    private boolean allOperational() {
        return board().equipment().stream().allMatch(e -> e.health().equals("OPERATIONAL"));
    }

    // --- tests --------------------------------------------------------------------------------------------------

    /** The real application selects the demo Liquibase context: the configured default would seed no accounts. */
    @Test
    @Order(0)
    void demoModeSeedsTheDemoAccounts() {
        assertThat(jdbc.queryForList("select username from app_user order by username", String.class))
                .containsExactly("admin1", "demo1", "supervisor1");
    }

    @Test
    @Order(1)
    void openApiIsServed() {
        assertThat(get(baseUrl() + "/api/v1/openapi.json").statusCode()).isEqualTo(200);
    }

    @Test
    @Order(2)
    void breakerMetricsAreExposed() {
        // a device's breaker (and its gauges) exists from its first full poll
        await().atMost(ofSeconds(20)).until(() -> get("http://127.0.0.1:" + managementPort
                + "/actuator/metrics/resilience4j.circuitbreaker.state").statusCode() == 200);
    }

    @Test
    @Order(3)
    void superviseFailAckAndLoseContact() {
        await().atMost(ofSeconds(20)).until(this::allOperational);
        long resumeFrom = board().lastEventId();
        try (SseRecorder sse = SseRecorder.open(baseUrl() + "/api/v1/stream?lastEventId=" + resumeFrom)) {
            fixture.centre().applyFault("RAD-N", new FaultRequest(FAIL, null));
            // null-safe assertions: an alarm that is not raised yet must fail the attempt, not the await
            await().atMost(ofSeconds(10)).untilAsserted(() -> {
                assertThat(eq("RAD-N").health()).isEqualTo("FAILED");
                assertThat(alarm("RAD-N", 101)).isNotNull().extracting(AlarmDto::severity).isEqualTo("CRITICAL");
                assertThat(alarm("SDPS-A", 401)).isNotNull().extracting(AlarmDto::instanceName).isEqualTo("RAD-N");
                assertThat(service("SURV")).isNotNull().extracting(ServiceDto::status).isEqualTo("OK");
            });
            await().atMost(ofSeconds(5)).until(() -> sse.has("equipment",
                    m -> m.data().contains("\"id\":\"RAD-N\"") && m.data().contains("\"health\":\"FAILED\"")));

            fixture.centre().applyFault("RAD-E", new FaultRequest(FAIL, null));
            await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(service("SURV")).isNotNull()
                    .extracting(ServiceDto::reasons).asInstanceOf(InstanceOfAssertFactories.LIST)
                    .contains("Fewer than 2 radars available"));

            AlarmDto acked = postAck(alarm("RAD-N", 101).id(), "seen");
            assertThat(acked.state()).isEqualTo("ACTIVE_ACK");
            assertThat(acked.ackedBy()).isEqualTo("supervisor1");
            await().atMost(ofSeconds(5)).until(() -> sse.has("alarm",
                    m -> m.data().contains("\"state\":\"ACTIVE_ACK\"")));

            fixture.centre().applyFault("FDPS-B", new FaultRequest(UNREACHABLE, null));
            await().atMost(ofSeconds(30)).untilAsserted(() -> {
                assertThat(eq("FDPS-B").unknownReason()).isEqualTo("LOST_CONTACT");
                assertThat(alarm("FDPS-B", 901)).isNotNull();
                assertThat(service("FD")).extracting(ServiceDto::status, ServiceDto::bestCase)
                        .containsExactly("UNKNOWN", "OK");
            });

            fixture.centre().applyFault("FDPS-B", new FaultRequest(RECOVER, null));
            // The probe restores contact and triggers a HALF_OPEN verification poll at once.
            await().atMost(ofSeconds(20)).untilAsserted(() -> {
                assertThat(eq("FDPS-B").health()).isEqualTo("OPERATIONAL");
                assertThat(openAlarm("FDPS-B", 901)).isNull();
                assertThat(get("http://127.0.0.1:" + managementPort + "/actuator/metrics/resilience4j.circuitbreaker.state"
                        + "?tag=name:FDPS-B&tag=state:closed").body()).contains("\"value\":1.0");
            });

            await().atMost(ofSeconds(10)).until(() -> sse.has("telemetry", m -> m.id() == null
                    && m.data().contains("\"equipmentId\":\"RAD-N\"")));
            await().atMost(ofSeconds(20)).until(() -> sse.has("heartbeat", m -> m.id() == null // every 15 s
                    && m.data().contains("\"serverTime\"")));
            List<Long> ids = sse.ids();
            assertThat(ids).isNotEmpty().isSorted().doesNotHaveDuplicates();
            assertThat(ids.getFirst()).isGreaterThan(resumeFrom);
            assertThat(sse.names()).doesNotContain("resync");

            // a browser that reconnects with the id it started from catches up with exactly the same events
            try (SseRecorder again = SseRecorder.open(baseUrl() + "/api/v1/stream", resumeFrom)) {
                await().atMost(ofSeconds(5)).until(() -> again.ids().size() >= ids.size());
                assertThat(again.ids()).startsWith(ids.toArray(Long[]::new));
            }
        }
    }

    // --- sign-in and roles over the real server; each test leaves signed out, so the next one signs in again ------

    @Test
    @Order(6)
    void aSupervisorPutsAUnitIntoMaintenanceAndTheCommandIsLogged() {
        HttpResponse<String> response = postCommand("FDPS-B", "MAINTENANCE_ON");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("\"outcome\":\"DONE\"");
        await().atMost(ofSeconds(10)).until(() -> maintenance("FDPS-B"));

        EquipmentDetailDto detail = getJson("/api/v1/equipment/FDPS-B", EquipmentDetailDto.class);
        assertThat(detail.recentEvents()).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo("EQUIPMENT_COMMANDED");
            assertThat(e.summary()).isEqualTo("Maintenance on by supervisor1: done");
        });

        assertThat(postCommand("FDPS-B", "MAINTENANCE_OFF").statusCode()).isEqualTo(200);
        await().atMost(ofSeconds(10)).until(() -> !maintenance("FDPS-B"));
    }

    private boolean maintenance(String id) {
        return board().equipment().stream().anyMatch(e -> e.id().equals(id) && e.maintenance());
    }

    private HttpResponse<String> postCommand(String id, String command) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/equipment/" + id + "/commands"))
                .timeout(ofSeconds(10)).header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrfToken())
                .POST(BodyPublishers.ofString("{\"command\":\"" + command + "\"}")).build());
    }

    @Test
    @Order(10)
    void anonymousRequestsAreRefused() {
        COOKIES.getCookieStore().removeAll();

        assertThat(get(baseUrl() + "/api/v1/board").statusCode()).isEqualTo(401);
        HttpResponse<String> stream = send(HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/stream"))
                .timeout(ofSeconds(10)).header("Accept", "text/event-stream").build());
        assertThat(stream.statusCode()).isEqualTo(401);
    }

    @Test
    @Order(11)
    void signInNeedsTheCsrfToken() {
        COOKIES.getCookieStore().removeAll();
        get(baseUrl() + "/api/v1/auth/config");

        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/auth/login"))
                .timeout(ofSeconds(10)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(BodyPublishers.ofString("username=supervisor1&password=supervisor1-demo")).build());

        assertThat(response.statusCode()).isEqualTo(403);
        COOKIES.getCookieStore().removeAll();
    }

    @Test
    @Order(12)
    void aViewerCannotAcknowledge() {
        COOKIES.getCookieStore().removeAll();
        assertThat(signIn("demo1", "demo1-demo").statusCode()).isEqualTo(200);
        assertThat(csrfToken()).as("sign-in hands back a fresh CSRF token").isNotEmpty();

        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/alarms/any/ack"))
                .timeout(ofSeconds(10)).header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrfToken())
                .POST(BodyPublishers.ofString("{}")).build());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("Your role does not allow this");
        COOKIES.getCookieStore().removeAll();
    }

    @Test
    @Order(13)
    void signingOutEndsTheOpenStream() {
        COOKIES.getCookieStore().removeAll();
        assertThat(signIn("supervisor1", "supervisor1-demo").statusCode()).isEqualTo(200);
        try (SseRecorder stream = SseRecorder.open(baseUrl() + "/api/v1/stream")) {
            HttpResponse<String> signOut = send(HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/auth/logout"))
                    .timeout(ofSeconds(10)).header("X-XSRF-TOKEN", csrfToken()).POST(BodyPublishers.noBody()).build());

            assertThat(signOut.statusCode()).isEqualTo(204);
            await().atMost(ofSeconds(5)).until(stream::ended);
        }
        COOKIES.getCookieStore().removeAll();
    }

    @Test
    @Order(4)
    void telemetrySamplesAreStored() {
        // one sample per device every 30 s while full polls succeed: two prove the cadence
        List<String> devices = Inventory.from(INVENTORY).devices().stream().map(Device::id).toList();
        await().atMost(ofSeconds(70)).until(() -> devices.stream().allMatch(id -> samples(id).size() >= 2));

        List<Long> radNorthSamples = samples("RAD-N");
        EquipmentDetailDto detail = getJson("/api/v1/equipment/RAD-N", EquipmentDetailDto.class);
        assertThat(detail.recentEvents()).isNotEmpty();
        assertThat(detail.recentEvents()).extracting(EventSummaryDto::type).doesNotContain("TELEMETRY_SAMPLED");
        assertThat(detail.recentEvents()).extracting(EventSummaryDto::id).doesNotContainAnyElementsOf(radNorthSamples);
    }

    private List<Long> samples(String equipmentId) {
        return jdbc.queryForList("select id from event_log where type = 'TELEMETRY_SAMPLED' and equipment_id = ?",
                Long.class, equipmentId);
    }

    @Test
    @Order(5)
    void trapLossIsReconciledFromTheAlarmTable() {
        await().atMost(ofSeconds(20)).until(() -> eq("RAD-S").health().equals("OPERATIONAL"));
        assertThat(raisedVia("RAD-S", 101)).isEmpty();

        // the device fails without telling anyone: only the next full poll's alarm table walk can find 101
        fixture.centre().applyFault("RAD-S", new FaultRequest(DROP_TRAPS, null));
        fixture.centre().applyFault("RAD-S", new FaultRequest(FAIL, null));
        await().atMost(ofSeconds(15)).untilAsserted(() -> {
            assertThat(openAlarm("RAD-S", 101)).isNotNull();
            assertThat(raisedVia("RAD-S", 101)).containsExactly("RESYNC");
        });

        fixture.centre().applyFault("RAD-S", new FaultRequest(RECOVER, null));
        await().atMost(ofSeconds(15)).until(() -> openAlarm("RAD-S", 101) == null);
        // the polls in between confirmed the alarm without raising it again
        assertThat(raisedVia("RAD-S", 101)).containsExactly("RESYNC");
    }

    /** The {@code via} of every stored ALARM_RAISED event of one alarm code; the codec keeps it in the payload. */
    private List<String> raisedVia(String equipmentId, int code) {
        return jdbc.queryForList("select payload->>'via' from event_log where type = 'ALARM_RAISED'"
                + " and equipment_id = ? and (payload->'alarm'->'key'->>'code')::int = ? order by id", String.class,
                equipmentId, code);
    }

    /** A minimal text/event-stream client on java.net.http that records every message until it is closed. */
    static final class SseRecorder implements AutoCloseable {

        record Message(String name, Long id, String data) {
        }

        private final HttpClient client = HttpClient.newBuilder().cookieHandler(COOKIES).build();
        private final List<Message> messages = new CopyOnWriteArrayList<>();
        private final Stream<String> lines;
        private final Thread reader;

        private SseRecorder(String url, Long lastEventId) {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(ofSeconds(10))
                    .header("Accept", "text/event-stream");
            if (lastEventId != null) {
                request.header("Last-Event-ID", String.valueOf(lastEventId));
            }
            HttpResponse<Stream<String>> response;
            try {
                response = client.send(request.build(), BodyHandlers.ofLines());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                    type -> assertThat(type).startsWith("text/event-stream"));
            lines = response.body();
            reader = Thread.ofVirtual().name("sse-recorder").start(this::read);
        }

        static SseRecorder open(String url) {
            return new SseRecorder(url, null);
        }

        /** Resumes like a reconnecting EventSource, with the {@code Last-Event-ID} header. */
        static SseRecorder open(String url, long lastEventId) {
            return new SseRecorder(url, lastEventId);
        }

        private void read() {
            String name = null;
            Long id = null;
            StringBuilder data = new StringBuilder();
            try {
                Iterator<String> it = lines.iterator();
                while (it.hasNext()) {
                    String line = it.next();
                    if (line.isEmpty()) {
                        if (name != null || !data.isEmpty()) {
                            messages.add(new Message(name == null ? "message" : name, id, data.toString()));
                        }
                        name = null;
                        id = null;
                        data.setLength(0);
                        continue;
                    }
                    int colon = line.indexOf(':');
                    String field = colon < 0 ? line : line.substring(0, colon);
                    String value = colon < 0 ? "" : line.substring(colon + 1);
                    value = value.startsWith(" ") ? value.substring(1) : value;
                    switch (field) {
                        case "event" -> name = value;
                        case "id" -> id = Long.valueOf(value);
                        case "data" -> data.append(data.isEmpty() ? "" : "\n").append(value);
                        default -> {
                        }
                    }
                }
            } catch (RuntimeException e) {
                // the stream was closed
            }
        }

        /** True once the server has closed the stream. */
        boolean ended() {
            return !reader.isAlive();
        }

        boolean has(String name, Predicate<Message> condition) {
            return messages.stream().anyMatch(m -> m.name().equals(name) && condition.test(m));
        }

        List<Long> ids() {
            return messages.stream().map(Message::id).filter(Objects::nonNull).toList();
        }

        List<String> names() {
            return messages.stream().map(Message::name).collect(Collectors.toList());
        }

        @Override
        public void close() {
            lines.close();
            client.shutdownNow();
            try {
                reader.join(Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
