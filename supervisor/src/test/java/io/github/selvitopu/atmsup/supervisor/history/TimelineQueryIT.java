package io.github.selvitopu.atmsup.supervisor.history;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmKey;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmSource;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmState;
import io.github.selvitopu.atmsup.supervisor.alarms.Via;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.AlarmEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.AlarmFloodEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.SupervisorEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.TelemetrySampleEvent;
import io.github.selvitopu.atmsup.supervisor.board.EventType;
import io.github.selvitopu.atmsup.supervisor.history.Timeline.Marker;
import io.github.selvitopu.atmsup.supervisor.history.Timeline.Span;
import java.time.Duration;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({HistoryTestBeans.class, TimelineQuery.class, TimelineQueryIT.Clock.class})
class TimelineQueryIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    private static final Instant T = Instant.parse("2026-01-01T10:00:00Z");

    @TestConfiguration
    static class Clock {
        @Bean
        InstantSource instantSource() {
            return InstantSource.fixed(T.plus(Duration.ofDays(1)));
        }
    }

    @Autowired
    private JpaBoardStore store;
    @Autowired
    private TimelineQuery query;
    @Autowired
    private EventLogRepository events;
    @Autowired
    private EventCodec codec;
    @Autowired
    private BoardHistory history;

    private static Instant at(int minute, int second) {
        return T.plusSeconds(minute * 60L + second);
    }

    private static SupervisorEvent supervisor(EventType type, Instant at) {
        return new SupervisorEvent(type, at, null);
    }

    private static AlarmEvent raised(String id, int code, boolean suppressed, Instant eventAt, Instant raisedAt) {
        return new AlarmEvent(EventType.ALARM_RAISED, eventAt, new Alarm(id, new AlarmKey("RAD-N", code, 1), "",
                Severity.CRITICAL, "Text " + code, AlarmSource.DEVICE, AlarmState.ACTIVE_UNACK, suppressed, raisedAt,
                null, null, null, null), Via.TRAP, null);
    }

    @Test
    void markersFloodsGapsAndRestartsInOneCall() {
        store.commit(List.<DomainEvent>of(
                supervisor(EventType.SUPERVISOR_STARTED, at(0, 0)),
                supervisor(EventType.SUPERVISOR_HEARTBEAT, at(1, 0)),
                raised("a1", 101, false, at(1, 30), at(1, 30)),
                new AlarmFloodEvent(EventType.ALARM_FLOOD_STARTED, at(1, 35), 5),
                raised("a2", 401, true, at(1, 40), at(1, 40)),
                new AlarmFloodEvent(EventType.ALARM_FLOOD_ENDED, at(1, 50), 0),
                supervisor(EventType.SUPERVISOR_HEARTBEAT, at(2, 0)),
                supervisor(EventType.SUPERVISOR_STOPPING, at(2, 10)),
                new TelemetrySampleEvent(at(5, 0), "RAD-N", at(5, 0), Map.of(), Map.of()), // a sample is no recording
                supervisor(EventType.SUPERVISOR_STARTED, at(10, 0)),
                supervisor(EventType.SUPERVISOR_HEARTBEAT, at(11, 0)),
                new AlarmFloodEvent(EventType.ALARM_FLOOD_STARTED, at(15, 0), 5)));

        Timeline all = query.timeline(at(0, 0), at(20, 0));

        assertThat(all.markers()).containsExactly(
                new Marker(at(1, 30), "RAD-N", 101, "CRITICAL", "Text 101", false),
                new Marker(at(1, 40), "RAD-N", 401, "CRITICAL", "Text 401", true));
        assertThat(all.truncated()).isFalse();
        assertThat(all.floods()).containsExactly(new Span(at(1, 35), at(1, 50)), new Span(at(15, 0), null));
        // 90 s after each event with no successor: the stop, the silence after the last heartbeat before the flood
        // event at 15:00, and the silence after it
        assertThat(all.gaps()).containsExactly(new Span(at(2, 10), at(10, 0)), new Span(at(12, 30), at(15, 0)),
                new Span(at(16, 30), null));
        assertThat(all.restarts()).containsExactly(at(0, 0), at(10, 0));

        // a window that starts inside the stop gap reports the gap with its true start, before the window
        Timeline later = query.timeline(at(5, 0), at(11, 0));
        assertThat(later.markers()).isEmpty();
        assertThat(later.floods()).isEmpty();
        assertThat(later.gaps()).containsExactly(new Span(at(2, 10), at(10, 0)));
        assertThat(later.restarts()).containsExactly(at(10, 0));

        Timeline midFlood = query.timeline(at(1, 45), at(1, 55));
        assertThat(midFlood.floods()).containsExactly(new Span(at(1, 35), at(1, 50)));
    }

    @Test
    void markersAreCappedNewestFirst() {
        List<DomainEvent> raises = new ArrayList<>();
        for (int i = 0; i < 2005; i++) {
            raises.add(raised("a" + i, 100 + i, false, T.plusSeconds(i), T.plusSeconds(i)));
        }
        store.commit(raises);

        Timeline timeline = query.timeline(T, T.plus(Duration.ofHours(1)));

        assertThat(timeline.truncated()).isTrue();
        assertThat(timeline.markers()).hasSize(2000);
        assertThat(timeline.markers().getFirst().at()).isEqualTo(T.plusSeconds(5));
        assertThat(timeline.markers().getLast().at()).isEqualTo(T.plusSeconds(2004));
    }

    @Test
    void theRaisedEndpointReturnsTheNewestStoredRaise() {
        Instant deviceRaisedAt = T.minus(Duration.ofHours(1));
        store.commit(List.<DomainEvent>of(
                raised("a1", 101, false, at(0, 10), at(0, 10)),
                raised("a2", 102, false, at(0, 15), at(0, 15)),
                raised("a1", 101, false, at(0, 20), deviceRaisedAt)));

        assertThat(events.findFirstByTypeAndEntityIdOrderByIdDesc("ALARM_RAISED", "a1"))
                .get().extracting(EventLogEntity::getOccurredAt).isEqualTo(at(0, 20));
        assertThat(events.findFirstByTypeAndEntityIdOrderByIdDesc("ALARM_RAISED", "a2"))
                .get().extracting(EventLogEntity::getOccurredAt).isEqualTo(at(0, 15));
        assertThat(events.findFirstByTypeAndEntityIdOrderByIdDesc("ALARM_RAISED", "none")).isEmpty();
    }

    @Test
    void aRestartEndsTheFloodItInterrupted() {
        store.commit(List.<DomainEvent>of(
                supervisor(EventType.SUPERVISOR_STARTED, at(0, 0)),
                new AlarmFloodEvent(EventType.ALARM_FLOOD_STARTED, at(1, 0), 5),
                supervisor(EventType.SUPERVISOR_STOPPING, at(2, 0)),
                supervisor(EventType.SUPERVISOR_STARTED, at(3, 0)),
                new AlarmFloodEvent(EventType.ALARM_FLOOD_STARTED, at(4, 0), 5),
                new AlarmFloodEvent(EventType.ALARM_FLOOD_ENDED, at(5, 0), 0)));

        assertThat(query.timeline(at(0, 0), at(6, 0)).floods())
                .containsExactly(new Span(at(1, 0), at(3, 0)), new Span(at(4, 0), at(5, 0)));
        // a later window does not see the interrupted flood as still open
        assertThat(query.timeline(at(3, 30), at(3, 50)).floods()).isEmpty();
    }

    @Test
    void gapsAreJudgedOnlyUpToNow() {
        store.commit(List.<DomainEvent>of(
                supervisor(EventType.SUPERVISOR_STARTED, at(0, 0)),
                supervisor(EventType.SUPERVISOR_HEARTBEAT, at(1, 0))));
        TimelineQuery running = new TimelineQuery(events, codec, history, InstantSource.fixed(at(1, 30)));
        TimelineQuery stopped = new TimelineQuery(events, codec, history, InstantSource.fixed(at(5, 0)));

        assertThat(running.timeline(at(0, 0), at(0, 0).plus(Duration.ofHours(1))).gaps()).isEmpty();
        assertThat(stopped.timeline(at(0, 0), at(0, 0).plus(Duration.ofHours(1))).gaps())
                .containsExactly(new Span(at(2, 30), null));
    }

    @Test
    void aGapKeepsItsTrueStartBeforeTheWindow() {
        store.commit(List.<DomainEvent>of(
                supervisor(EventType.SUPERVISOR_STARTED, at(0, 0)),
                supervisor(EventType.SUPERVISOR_STOPPING, at(1, 0)),
                supervisor(EventType.SUPERVISOR_STARTED, at(30, 0))));

        assertThat(query.timeline(at(10, 0), at(31, 0)).gaps()).containsExactly(new Span(at(1, 0), at(30, 0)));
    }
}
