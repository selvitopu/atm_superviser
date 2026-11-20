package io.github.selvitopu.atmsup.supervisor.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmKey;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmSource;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmState;
import io.github.selvitopu.atmsup.supervisor.alarms.Correlation;
import io.github.selvitopu.atmsup.supervisor.alarms.CorrelationRule;
import io.github.selvitopu.atmsup.supervisor.alarms.Via;
import io.github.selvitopu.atmsup.supervisor.board.Contact;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.AlarmEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.EquipmentEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.ServiceEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.SupervisorEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.TelemetrySampleEvent;
import io.github.selvitopu.atmsup.supervisor.board.EquipmentState;
import io.github.selvitopu.atmsup.supervisor.board.EventType;
import io.github.selvitopu.atmsup.supervisor.board.Health;
import io.github.selvitopu.atmsup.supervisor.board.ServiceId;
import io.github.selvitopu.atmsup.supervisor.board.ServiceState;
import io.github.selvitopu.atmsup.supervisor.board.ServiceStatus;
import io.github.selvitopu.atmsup.supervisor.board.StoredEvent;
import io.github.selvitopu.atmsup.supervisor.board.UnknownReason;
import io.github.selvitopu.atmsup.supervisor.CapturedLogs;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({EventCodec.class, JpaBoardStore.class, JpaBoardStoreIT.FixedClock.class})
class JpaBoardStoreIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    private static final Instant t0 = Instant.parse("2026-01-01T10:00:00.123Z");
    private static final Instant RECORDED = t0.plusMillis(7);

    @TestConfiguration
    static class FixedClock {
        @Bean
        InstantSource instantSource() {
            return InstantSource.fixed(RECORDED);
        }
    }

    @Autowired
    private JpaBoardStore store;
    @Autowired
    private EventLogRepository eventLog;
    @Autowired
    private AlarmRepository alarms;
    @Autowired
    private EventCodec codec;
    @Autowired
    private JsonMapper json;
    @Autowired
    private TestEntityManager em;

    private static Alarm alarm(String id, AlarmKey key, AlarmState state) {
        return new Alarm(id, key, "Channel A", Severity.CRITICAL, "Transmitter fault", AlarmSource.DEVICE, state,
                false, t0, null, null, null, null);
    }

    private static Alarm deviceAlarm(String id, AlarmState state) {
        return alarm(id, new AlarmKey("RAD-N", 101, 1), state);
    }

    private static Alarm acknowledged(Alarm a, AlarmState state) {
        return new Alarm(a.id(), a.key(), a.instanceName(), a.severity(), a.text(), a.source(), state, a.suppressed(),
                a.raisedAt(), t0.plusSeconds(1), "demo-user", "seen", state == AlarmState.CLOSED ? t0 : null);
    }

    private static AlarmEvent event(EventType type, Alarm a, Via via, String actor) {
        return new AlarmEvent(type, t0, a, via, actor);
    }

    private List<EventLogEntity> rowsFromDatabase() {
        em.flush();
        em.clear();
        return eventLog.findAll(Sort.by("id"));
    }

    @Test
    void commitRoundTripsThroughCodec() {
        assertThat(store.hasEvents()).isFalse();
        Alarm raised = deviceAlarm("a1", AlarmState.ACTIVE_UNACK);
        Alarm failed = new Alarm("a2", new AlarmKey("SURV", 904, 0), "", Severity.CRITICAL,
                "Service failed: Surveillance", AlarmSource.SUPERVISOR, AlarmState.ACTIVE_UNACK, false, t0, null, null,
                null, null);
        List<DomainEvent> events = List.of(
                new EquipmentEvent(EventType.EQUIPMENT_STATE_CHANGED, t0, new EquipmentState("RAD-N",
                        Health.OPERATIONAL, null, false, Role.STANDALONE, t0, new Contact(true, t0, null))),
                new EquipmentEvent(EventType.CONTACT_LOST, t0, new EquipmentState("SDPS-A", Health.UNKNOWN,
                        UnknownReason.LOST_CONTACT, true, Role.ACTIVE, null, new Contact(false, t0.minusSeconds(15),
                        t0))),
                event(EventType.ALARM_RAISED, raised, Via.TRAP, null),
                event(EventType.ALARM_ACKNOWLEDGED, acknowledged(raised, AlarmState.ACTIVE_ACK), Via.OPERATOR,
                        "demo-user"),
                new ServiceEvent(t0, new ServiceState(ServiceId.SURV, ServiceStatus.FAILED, null, true, false,
                        List.of("No SDPS unit available"), t0)),
                event(EventType.ALARM_RAISED, failed, Via.SUPERVISOR, null),
                new TelemetrySampleEvent(t0, "SDPS-A", t0.minusMillis(5),
                        Map.of("trackCount", 412L, "swVersion", "4.2.1"),
                        Map.of("sensors", List.of(Map.of("index", 1L, "name", "RAD-N", "inputStatus", "OK")))),
                new SupervisorEvent(EventType.SUPERVISOR_STARTED, t0, UnknownReason.NOT_YET_POLLED),
                new SupervisorEvent(EventType.SUPERVISOR_HEARTBEAT, t0, null));

        List<StoredEvent> stored = store.commit(events);
        List<EventLogEntity> rows = rowsFromDatabase();

        assertThat(rows.stream().map(codec::decode).toList()).isEqualTo(events);
        assertThat(stored).extracting(StoredEvent::event).isEqualTo(events);
        assertThat(stored).extracting(StoredEvent::id).isEqualTo(rows.stream().map(EventLogEntity::getId).toList())
                .isSortedAccordingTo(Long::compare).doesNotHaveDuplicates();
        assertThat(rows).extracting(EventLogEntity::getType, EventLogEntity::getEntityKind,
                EventLogEntity::getEntityId, EventLogEntity::getEquipmentId, EventLogEntity::getActor)
                .containsExactly(
                        tuple("EQUIPMENT_STATE_CHANGED", "EQUIPMENT", "RAD-N", "RAD-N", null),
                        tuple("CONTACT_LOST", "EQUIPMENT", "SDPS-A", "SDPS-A", null),
                        tuple("ALARM_RAISED", "ALARM", "a1", "RAD-N", null),
                        tuple("ALARM_ACKNOWLEDGED", "ALARM", "a1", "RAD-N", "demo-user"),
                        tuple("SERVICE_STATE_CHANGED", "SERVICE", "SURV", null, null),
                        tuple("ALARM_RAISED", "ALARM", "a2", "SURV", null),
                        tuple("TELEMETRY_SAMPLED", "TELEMETRY", "SDPS-A", "SDPS-A", null),
                        tuple("SUPERVISOR_STARTED", "SUPERVISOR", null, null, null),
                        tuple("SUPERVISOR_HEARTBEAT", "SUPERVISOR", null, null, null));
        assertThat(rows).extracting(EventLogEntity::getOccurredAt, EventLogEntity::getRecordedAt)
                .containsOnly(tuple(t0, RECORDED));
        assertThat(store.loadOpenAlarms()).containsExactlyInAnyOrder(acknowledged(raised, AlarmState.ACTIVE_ACK),
                failed);
        assertThat(store.hasEvents()).isTrue();
    }

    @Test
    void lastEquipmentStatesAreTheNewestEquipmentEventPerDevice() {
        EquipmentState first = new EquipmentState("RAD-N", Health.OPERATIONAL, null, false, Role.STANDALONE, t0,
                new Contact(true, t0, null));
        EquipmentState lost = new EquipmentState("RAD-N", Health.UNKNOWN, UnknownReason.LOST_CONTACT, true,
                Role.STANDALONE, null, new Contact(false, t0, t0.plusSeconds(15)));
        store.commit(List.of(
                new EquipmentEvent(EventType.EQUIPMENT_STATE_CHANGED, t0, first),
                new EquipmentEvent(EventType.CONTACT_LOST, t0.plusSeconds(15), lost),
                event(EventType.ALARM_RAISED, deviceAlarm("a1", AlarmState.ACTIVE_UNACK), Via.TRAP, null)));
        store.commit(List.of(
                new ServiceEvent(t0, new ServiceState(ServiceId.FD, ServiceStatus.OK, null, false, false, List.of(),
                        t0)),
                new TelemetrySampleEvent(t0, "SDPS-A", t0, Map.of("trackCount", 1L), Map.of())));
        em.flush();
        em.clear();

        assertThat(store.loadLastEquipmentStates()).containsOnlyKeys("RAD-N").containsEntry("RAD-N", lost);
    }

    @Test
    void payloadsHaveTheDocumentedShapes() {
        store.commit(List.of(
                new EquipmentEvent(EventType.EQUIPMENT_STATE_CHANGED, t0, new EquipmentState("RAD-N",
                        Health.OPERATIONAL, null, false, Role.STANDALONE, t0, new Contact(true, t0, null))),
                new ServiceEvent(t0, new ServiceState(ServiceId.FD, ServiceStatus.OK, null, false, false, List.of(),
                        t0)),
                event(EventType.ALARM_RAISED, deviceAlarm("a1", AlarmState.ACTIVE_UNACK), Via.TRAP, null),
                new SupervisorEvent(EventType.SUPERVISOR_STARTED, t0, UnknownReason.SUPERVISOR_RESTARTED),
                new TelemetrySampleEvent(t0, "RAD-N", t0, Map.of("plotsPerScan", 300L), Map.of())));

        List<JsonNode> payloads = rowsFromDatabase().stream().map(r -> json.readTree(r.getPayload())).toList();

        assertThat(payloads).extracting(p -> p.get("schemaVersion").asInt()).containsOnly(1);
        assertThat(payloads).extracting(p -> p.propertyNames().stream().sorted().toList()).containsExactly(
                List.of("schemaVersion", "state"),
                List.of("schemaVersion", "state"),
                List.of("alarm", "schemaVersion", "via"),
                List.of("reason", "schemaVersion"),
                List.of("equipmentId", "observedAt", "schemaVersion", "tables", "values"));
        assertThat(payloads.get(2).get("via").asString()).isEqualTo("TRAP");
        assertThat(payloads.get(3).get("reason").asString()).isEqualTo("SUPERVISOR_RESTARTED");
        assertThat(payloads.get(4).get("observedAt").asString()).isEqualTo("2026-01-01T10:00:00.123Z");
    }

    @Test
    void instantsAreStoredAsUtcMilliseconds() {
        store.commit(List.of(new SupervisorEvent(EventType.SUPERVISOR_HEARTBEAT, t0, null)));
        em.flush();

        Object stored = em.getEntityManager()
                .createNativeQuery("select to_char(occurred_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US') from event_log")
                .getSingleResult();

        assertThat(stored).isEqualTo("2026-01-01T10:00:00.123000");
    }

    @Test
    void openAlarmIdentityIsUnique() {
        alarms.saveAndFlush(new AlarmEntity(deviceAlarm("a1", AlarmState.ACTIVE_UNACK)));

        try (CapturedLogs logs = new CapturedLogs("org.hibernate.orm.jdbc.error")) {
            assertThatThrownBy(() -> alarms.saveAndFlush(new AlarmEntity(deviceAlarm("a2",
                    AlarmState.ACTIVE_UNACK)))).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(logs.warnings()).anyMatch(w -> w.contains("uk_alarm_open"));
        }
    }

    @Test
    void closedAlarmFreesItsIdentity() {
        Alarm a1 = deviceAlarm("a1", AlarmState.ACTIVE_UNACK);
        Alarm a2 = deviceAlarm("a2", AlarmState.ACTIVE_UNACK);

        store.commit(List.of(event(EventType.ALARM_RAISED, a1, Via.TRAP, null),
                event(EventType.ALARM_CLOSED, acknowledged(a1, AlarmState.CLOSED), Via.OPERATOR, "demo-user"),
                event(EventType.ALARM_RAISED, a2, Via.TRAP, null)));
        em.flush();
        em.clear();

        assertThat(store.loadOpenAlarms()).containsExactly(a2);
        assertThat(alarms.findById("a1")).get().extracting(AlarmEntity::toAlarm)
                .isEqualTo(acknowledged(a1, AlarmState.CLOSED));
    }

    @Test
    void openAlarmsLoadWithTheirCorrelation() {
        Alarm cause = deviceAlarm("a1", AlarmState.ACTIVE_UNACK);
        Alarm correlated = alarm("a2", new AlarmKey("SDPS-A", 401, 0), AlarmState.ACTIVE_UNACK)
                .withCorrelation(new Correlation("a1", "RAD-N", 101, CorrelationRule.SENSOR_INPUT));

        store.commit(List.of(event(EventType.ALARM_RAISED, cause, Via.TRAP, null),
                event(EventType.ALARM_RAISED, correlated, Via.TRAP, null)));
        em.flush();
        em.clear();

        assertThat(store.loadOpenAlarms()).containsExactlyInAnyOrder(cause, correlated);
        assertThat(alarms.findById("a2")).get().extracting(a -> a.toAlarm().correlation())
                .isEqualTo(new Correlation("a1", "RAD-N", 101, CorrelationRule.SENSOR_INPUT));
    }

    @Test
    void anAdoptionAndADetachmentUpdateTheStoredLink() {
        Alarm raised = alarm("a2", new AlarmKey("SDPS-A", 401, 0), AlarmState.ACTIVE_UNACK);
        Alarm adopted = raised.withCorrelation(new Correlation("a1", "RAD-N", 101, CorrelationRule.SENSOR_INPUT));

        store.commit(List.of(event(EventType.ALARM_RAISED, raised, Via.TRAP, null)));
        em.flush();
        em.clear();
        assertThat(store.loadOpenAlarms()).containsExactly(raised);

        store.commit(List.of(event(EventType.ALARM_UPDATED, adopted, Via.CORRELATION, null)));
        em.flush();
        em.clear();
        assertThat(store.loadOpenAlarms()).containsExactly(adopted);
        assertThat(store.loadOpenAlarms().getFirst().correlation())
                .isEqualTo(new Correlation("a1", "RAD-N", 101, CorrelationRule.SENSOR_INPUT));

        store.commit(List.of(event(EventType.ALARM_UPDATED, raised, Via.CORRELATION, null)));
        em.flush();
        em.clear();
        assertThat(store.loadOpenAlarms()).containsExactly(raised);
        assertThat(store.loadOpenAlarms().getFirst().correlation()).isNull();
    }

    @Test
    void aPartialCorrelationIsRejected() {
        try (CapturedLogs logs = new CapturedLogs("org.hibernate.orm.jdbc.error")) {
            assertThatThrownBy(() -> em.getEntityManager().createNativeQuery(
                    "insert into alarm (id, equipment_id, code, instance, severity, text, source, state, suppressed,"
                            + " raised_at, cause_id, cause_equipment_id, cause_code, correlation_rule)"
                            + " values ('p1', 'SDPS-A', 401, 0, 'MINOR', 'x', 'DEVICE', 'ACTIVE_UNACK', false, now(),"
                            + " 'a1', 'RAD-N', 101, null)").executeUpdate())
                    .hasMessageContaining("ck_alarm_correlation");
        }
    }
}
