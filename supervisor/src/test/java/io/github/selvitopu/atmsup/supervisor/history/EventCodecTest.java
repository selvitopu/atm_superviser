package io.github.selvitopu.atmsup.supervisor.history;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmKey;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmSource;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmState;
import io.github.selvitopu.atmsup.supervisor.alarms.Correlation;
import io.github.selvitopu.atmsup.supervisor.alarms.CorrelationRule;
import io.github.selvitopu.atmsup.supervisor.alarms.Via;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.AlarmEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.CommandEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.ServiceEvent;
import io.github.selvitopu.atmsup.supervisor.board.EventType;
import io.github.selvitopu.atmsup.supervisor.board.ServiceId;
import io.github.selvitopu.atmsup.supervisor.board.ServiceState;
import io.github.selvitopu.atmsup.supervisor.board.ServiceStatus;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The codec with the {@code JsonMapper} that Spring Boot configures for the application. */
class EventCodecTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00.123Z");

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withBean(EventCodec.class);

    private void withCodec(Consumer<EventCodec> test) {
        context.run(ctx -> test.accept(ctx.getBean(EventCodec.class)));
    }

    private static EventLogEntity serviceRow(String payload) {
        return new EventLogEntity(T0, T0, EventType.SERVICE_STATE_CHANGED.name(), "SERVICE", "SURV", null, null,
                payload);
    }

    @Test
    void aServicePayloadStoredBeforeRedundancyUnconfirmedExistedDecodesItAsFalse() {
        // the shape v0.1.1 stored before the component was added
        String stored = """
                {"schemaVersion":1,"state":{"bestCase":null,"id":"SURV","reasons":["Only one SDPS unit available"],\
                "redundancyLost":true,"since":"2026-01-01T10:00:00.123Z","status":"DEGRADED"}}""";

        withCodec(codec -> assertThat(codec.decode(serviceRow(stored))).isEqualTo(new ServiceEvent(T0,
                new ServiceState(ServiceId.SURV, ServiceStatus.DEGRADED, null, true, false,
                        List.of("Only one SDPS unit available"), T0))));
    }

    @Test
    void redundancyUnconfirmedRoundTrips() {
        var event = new ServiceEvent(T0, new ServiceState(ServiceId.SURV, ServiceStatus.DEGRADED, null, false, true,
                List.of("Fewer than 2 radars available",
                        "Unconfirmed: Only one SDPS unit available (equipment in UNKNOWN state: SDPS-A)"), T0));

        withCodec(codec -> {
            EventLogEntity row = codec.encode(event, T0);
            assertThat(row.getPayload()).contains("\"redundancyUnconfirmed\":true");
            assertThat(codec.decode(row)).isEqualTo(event);
        });
    }

    @Test
    void aCommandEventRoundTripsWithItsActorAndEquipment() {
        var event = new CommandEvent(T0, "FDPS-B", "RESTART", "supervisor1", "REFUSED");
        var intent = new CommandEvent(T0, "FDPS-B", "RESTART", "supervisor1", "ISSUED");

        withCodec(codec -> {
            EventLogEntity row = codec.encode(event, T0);
            assertThat(row.getEquipmentId()).isEqualTo("FDPS-B");
            assertThat(row.getActor()).isEqualTo("supervisor1");
            assertThat(codec.decode(row)).isEqualTo(event);
            assertThat(codec.decode(codec.encode(intent, T0))).isEqualTo(intent);
        });
    }

    @Test
    void aCorrelatedAlarmRoundTrips() {
        var alarm = new Alarm("a2", new AlarmKey("SDPS-A", 401, 0), "RAD-N", Severity.MINOR, "Sensor input lost",
                AlarmSource.DEVICE, AlarmState.ACTIVE_UNACK, false, T0, null, null, null, null,
                new Correlation("a1", "RAD-N", 101, CorrelationRule.SENSOR_INPUT));
        var event = new AlarmEvent(EventType.ALARM_UPDATED, T0, alarm, Via.CORRELATION, null);

        withCodec(codec -> {
            EventLogEntity row = codec.encode(event, T0);
            assertThat(row.getPayload()).contains("\"correlation\"").contains("\"SENSOR_INPUT\"");
            assertThat(codec.decode(row)).isEqualTo(event);
        });
    }

    @Test
    void anAlarmPayloadWithoutCorrelationDecodesAsUncorrelated() {
        // the shape stored before correlations existed
        String stored = """
                {"schemaVersion":1,"via":"TRAP","alarm":{"id":"a1","key":{"equipmentId":"RAD-N","code":101,\
                "instance":0},"instanceName":"","severity":"CRITICAL","text":"Transmitter fault","source":"DEVICE",\
                "state":"ACTIVE_UNACK","suppressed":false,"raisedAt":"2026-01-01T10:00:00.123Z","ackedAt":null,\
                "ackedBy":null,"ackComment":null,"clearedAt":null}}""";
        var row = new EventLogEntity(T0, T0, EventType.ALARM_RAISED.name(), "ALARM", "a1", "RAD-N", null, stored);

        withCodec(codec -> {
            var event = (AlarmEvent) codec.decode(row);
            assertThat(event.alarm().correlation()).isNull();
            assertThat(event.alarm().id()).isEqualTo("a1");
        });
    }
}
