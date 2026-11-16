package io.github.selvitopu.atmsup.supervisor.simconsole;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The simulator's console API, forwarded so that injecting a fault needs the same sign-in and role as any other
 * action. It exists only in demo mode: a real centre has no simulator, and a training fault must never reach one.
 * Answers pass through unchanged, the simulator's refusals included.
 */
@RestController
@RequestMapping("/api/v1/simulator")
@ConditionalOnProperty(name = "atm.demo-mode", havingValue = "true")
@PreAuthorize("hasRole('SUPERVISOR')")
public class SimulatorConsoleController {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient simulator;

    /** A builder bean, if one exists (tests bind a mock server to it); otherwise a client with short timeouts. */
    public SimulatorConsoleController(ObjectProvider<RestClient.Builder> builders,
                                      @Value("${atm.simulator.console-url:http://localhost:8090}") String consoleUrl) {
        RestClient.Builder builder = builders.getIfAvailable(() -> {
            SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
            timeouts.setConnectTimeout(CONNECT_TIMEOUT);
            timeouts.setReadTimeout(READ_TIMEOUT);
            return RestClient.builder().requestFactory(timeouts);
        });
        this.simulator = builder.baseUrl(consoleUrl).build();
    }

    @GetMapping("/devices")
    public ResponseEntity<?> devices() {
        return forward(simulator.get().uri("/api/devices"));
    }

    @PostMapping("/devices/{id}/faults")
    public ResponseEntity<?> fault(@PathVariable String id, @RequestBody String fault) {
        return forward(simulator.post().uri("/api/devices/{id}/faults", id)
                .contentType(MediaType.APPLICATION_JSON).body(fault));
    }

    @PostMapping("/reset")
    public ResponseEntity<?> reset() {
        return forward(simulator.post().uri("/api/reset"));
    }

    private static ResponseEntity<?> forward(RestClient.RequestHeadersSpec<?> request) {
        try {
            return request.<ResponseEntity<?>>exchange((sent, answer) -> {
                byte[] body = answer.getBody().readAllBytes();
                ResponseEntity.BodyBuilder entity = ResponseEntity.status(answer.getStatusCode());
                MediaType type = answer.getHeaders().getContentType();
                if (type != null) {
                    entity.contentType(type);
                }
                return body.length == 0 ? entity.build() : entity.body(new String(body, StandardCharsets.UTF_8));
            });
        } catch (ResourceAccessException unreachable) {
            return ResponseEntity.of(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                    "The simulator does not answer")).build();
        }
    }
}
