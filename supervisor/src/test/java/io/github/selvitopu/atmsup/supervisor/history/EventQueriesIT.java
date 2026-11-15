package io.github.selvitopu.atmsup.supervisor.history;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmKey;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmSource;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmState;
import io.github.selvitopu.atmsup.supervisor.board.Contact;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.EquipmentEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.TelemetrySampleEvent;
import io.github.selvitopu.atmsup.supervisor.board.EquipmentState;
import io.github.selvitopu.atmsup.supervisor.board.EventType;
import io.github.selvitopu.atmsup.supervisor.board.Health;
import io.github.selvitopu.atmsup.supervisor.board.StoredEvent;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({EventCodec.class, JpaBoardStore.class, EventQueries.class, EventQueriesIT.Clock.class})
class EventQueriesIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    private static final Instant T = Instant.parse("2026-01-01T10:00:00Z");

    @TestConfiguration
    static class Clock {
        @Bean
        InstantSource instantSource() {
            return InstantSource.fixed(T);
        }
    }

    @Autowired
    private JpaBoardStore store;
    @Autowired
    private EventQueries queries;

    private static EquipmentEvent state(String id, int second) {
        return new EquipmentEvent(EventType.EQUIPMENT_STATE_CHANGED, T.plusSeconds(second), new EquipmentState(id,
                Health.OPERATIONAL, null, false, Role.STANDALONE, T, new Contact(true, T, null)));
    }

    private static TelemetrySampleEvent sample(String id, int second) {
        return new TelemetrySampleEvent(T.plusSeconds(second), id, T, Map.of("plotsPerScan", 300L), Map.of());
    }

    private static Alarm alarm(String id, String equipment, int code, Severity s, AlarmState state, int raisedSecond) {
        return new Alarm(id, new AlarmKey(equipment, code, 1), "", s, "Text " + code, AlarmSource.DEVICE, state,
                false, T.plusSeconds(raisedSecond), null, null, null, null);
    }

    private void saveAlarm(Alarm a) {
        store.commit(List.of(new DomainEvent.AlarmEvent(EventType.ALARM_RAISED, a.raisedAt(), a,
                io.github.selvitopu.atmsup.supervisor.alarms.Via.TRAP, null)));
    }

    @Test
    void recentEventsAreNewestFirstPerEquipmentAndSkipTelemetrySamples() {
        List<StoredEvent> stored = new ArrayList<>(store.commit(List.of(state("RAD-N", 1), state("RAD-E", 2),
                sample("RAD-N", 3), state("RAD-N", 4), sample("RAD-N", 5))));

        List<StoredEvent> recent = queries.recentForEquipment("RAD-N", 10);

        assertThat(recent).extracting(StoredEvent::id).containsExactly(stored.get(3).id(), stored.get(0).id());
        assertThat(recent).extracting(e -> e.event().type()).containsOnly(EventType.EQUIPMENT_STATE_CHANGED);
        assertThat(queries.recentForEquipment("RAD-N", 1)).extracting(StoredEvent::id)
                .containsExactly(stored.get(3).id());
    }

    @Test
    void searchFiltersAndSortsAlarms() {
        saveAlarm(alarm("a1", "RAD-N", 401, Severity.MINOR, AlarmState.ACTIVE_UNACK, 1));
        saveAlarm(alarm("a2", "RAD-N", 402, Severity.MAJOR, AlarmState.ACTIVE_UNACK, 2));
        saveAlarm(alarm("a3", "RAD-E", 401, Severity.MINOR, AlarmState.CLOSED, 3));
        saveAlarm(alarm("a4", "RAD-E", 402, Severity.MINOR, AlarmState.CLEARED_UNACK, 4));
        PageRequest first = PageRequest.of(0, 10);

        assertThat(ids(queries.searchAlarms("all", null, null, null, null, first)))
                .containsExactly("a4", "a3", "a2", "a1");
        assertThat(ids(queries.searchAlarms("open", null, null, null, null, first)))
                .containsExactly("a4", "a2", "a1");
        assertThat(ids(queries.searchAlarms("all", Severity.MINOR, "RAD-E", null, null, first)))
                .containsExactly("a4", "a3");
        assertThat(ids(queries.searchAlarms("all", null, null, T.plusSeconds(2), T.plusSeconds(4), first)))
                .containsExactly("a3", "a2");
    }

    @Test
    void searchPaginates() {
        for (int i = 1; i <= 5; i++) {
            saveAlarm(alarm("a" + i, "RAD-N", 400 + i, Severity.MINOR, AlarmState.ACTIVE_UNACK, i));
        }
        Page<Alarm> page = queries.searchAlarms("open", null, null, null, null, PageRequest.of(1, 2));

        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(ids(page)).containsExactly("a3", "a2");
    }

    private static List<String> ids(Page<Alarm> page) {
        return page.getContent().stream().map(Alarm::id).toList();
    }
}
