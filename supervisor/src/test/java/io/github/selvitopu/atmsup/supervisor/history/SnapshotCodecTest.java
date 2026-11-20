package io.github.selvitopu.atmsup.supervisor.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmKey;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmSource;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmState;
import io.github.selvitopu.atmsup.supervisor.alarms.Correlation;
import io.github.selvitopu.atmsup.supervisor.alarms.CorrelationRule;
import io.github.selvitopu.atmsup.supervisor.board.AlarmFlood;
import io.github.selvitopu.atmsup.supervisor.board.Board;
import io.github.selvitopu.atmsup.supervisor.board.Contact;
import io.github.selvitopu.atmsup.supervisor.board.EquipmentState;
import io.github.selvitopu.atmsup.supervisor.board.Health;
import io.github.selvitopu.atmsup.supervisor.board.ServiceId;
import io.github.selvitopu.atmsup.supervisor.board.ServiceState;
import io.github.selvitopu.atmsup.supervisor.board.ServiceStatus;
import io.github.selvitopu.atmsup.supervisor.board.UnknownReason;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The snapshot codec with the {@code JsonMapper} that Spring Boot configures for the application. */
class SnapshotCodecTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00.123Z");

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withBean(SnapshotCodec.class);

    private void withCodec(Consumer<SnapshotCodec> test) {
        context.run(ctx -> test.accept(ctx.getBean(SnapshotCodec.class)));
    }

    private static Board board() {
        Alarm cause = new Alarm("a1", new AlarmKey("RAD-N", 101, 1), "Channel A", Severity.CRITICAL, "Transmitter fault",
                AlarmSource.DEVICE, AlarmState.ACTIVE_UNACK, false, T0, null, null, null, null);
        Alarm effect = new Alarm("a2", new AlarmKey("SURV", 904, 0), "", Severity.CRITICAL, "Service failed",
                AlarmSource.SUPERVISOR, AlarmState.ACTIVE_ACK, true, T0, T0.plusSeconds(1), "supervisor1", "seen",
                null, new Correlation("a1", "RAD-N", 101, CorrelationRule.SENSOR_INPUT));
        return new Board(
                Map.of("RAD-N", new EquipmentState("RAD-N", Health.OPERATIONAL, null, false, Role.STANDALONE, T0,
                                new Contact(true, T0, null)),
                        "SDPS-A", new EquipmentState("SDPS-A", Health.UNKNOWN, UnknownReason.LOST_CONTACT, true,
                                Role.ACTIVE, null, new Contact(false, T0.minusSeconds(15), T0))),
                Map.of(ServiceId.SURV, new ServiceState(ServiceId.SURV, ServiceStatus.DEGRADED, null, true, false,
                        List.of("Only one SDPS unit available"), T0)),
                Map.of(cause.key(), cause, effect.key(), effect), 42, new AlarmFlood(true, T0));
    }

    @Test
    void aBoardRoundTrips() {
        withCodec(codec -> {
            Board b = board();
            assertThat(codec.decode(codec.encode(b), 42)).isEqualTo(b);
        });
    }

    @Test
    void anUnknownSchemaVersionIsRefused() {
        withCodec(codec -> {
            String json = codec.encode(board()).replace("\"schemaVersion\":1", "\"schemaVersion\":2");
            assertThatThrownBy(() -> codec.decode(json, 42)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("schema version 2");
        });
    }
}
