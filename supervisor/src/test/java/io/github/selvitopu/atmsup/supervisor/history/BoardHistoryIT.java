package io.github.selvitopu.atmsup.supervisor.history;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmCatalogue;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmKey;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmSource;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmState;
import io.github.selvitopu.atmsup.supervisor.alarms.Correlation;
import io.github.selvitopu.atmsup.supervisor.alarms.CorrelationRule;
import io.github.selvitopu.atmsup.supervisor.alarms.Via;
import io.github.selvitopu.atmsup.supervisor.board.AlarmFlood;
import io.github.selvitopu.atmsup.supervisor.board.Board;
import io.github.selvitopu.atmsup.supervisor.board.Contact;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.AlarmEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.AlarmFloodEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.CommandEvent;
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
import io.github.selvitopu.atmsup.supervisor.board.SnapshotStore;
import io.github.selvitopu.atmsup.supervisor.board.UnknownReason;
import java.time.Instant;
import java.time.InstantSource;
import java.util.LinkedHashMap;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The board at an instant, folded from the stored log with scripted times; the gap threshold is the default 90 s. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({HistoryTestBeans.class, BoardHistoryIT.Clock.class})
class BoardHistoryIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    private static final Instant T0 = Instant.parse("2026-01-01T14:00:00Z");
    private static final Instant NOW = Instant.parse("2026-01-01T16:00:00Z");
    private static final List<String> UNITS =
            HistoryTestBeans.INVENTORY.devices().stream().map(Device::id).toList();

    @TestConfiguration
    static class Clock {
        @Bean
        InstantSource instantSource() {
            return InstantSource.fixed(NOW);
        }
    }

    @Autowired
    private JpaBoardStore store;
    @Autowired
    private SnapshotStore snapshots;
    @Autowired
    private BoardHistory history;
    @Autowired
    private JdbcTemplate jdbc;

    private static Instant at(String time) {
        return Instant.parse("2026-01-01T" + time + "Z");
    }

    private static Instant t(int seconds) {
        return T0.plusSeconds(seconds);
    }

    private long commit(DomainEvent... events) {
        return store.commit(List.of(events)).getLast().id();
    }

    private static SupervisorEvent supervisor(EventType type, Instant at) {
        return new SupervisorEvent(type, at, type == EventType.SUPERVISOR_STARTED
                ? UnknownReason.SUPERVISOR_RESTARTED : null);
    }

    private static EquipmentEvent equipment(EventType type, Instant at, EquipmentState s) {
        return new EquipmentEvent(type, at, s);
    }

    private static EquipmentState state(String id, Health health, Role role, Instant since, Instant contactAt) {
        return new EquipmentState(id, health, null, false, role, since, new Contact(true, contactAt, null));
    }

    private static EquipmentState operational(String id, Instant since) {
        return state(id, Health.OPERATIONAL, Role.STANDALONE, since, since);
    }

    private static TelemetrySampleEvent sample(String id, Instant at, long plots) {
        return new TelemetrySampleEvent(at, id, at.minusMillis(5), Map.of("plotsPerScan", plots),
                Map.of("channels", List.of(Map.of("index", 1L, "status", "OK"))));
    }

    private static Alarm alarm(String id, String unit, int code, Severity severity, AlarmSource source, String text,
                               Instant raisedAt) {
        return new Alarm(id, new AlarmKey(unit, code, 0), "", severity, text, source, AlarmState.ACTIVE_UNACK, false,
                raisedAt, null, null, null, null);
    }

    private static Alarm withState(Alarm a, AlarmState state, Instant at) {
        boolean acked = state == AlarmState.ACTIVE_ACK || state == AlarmState.CLOSED;
        boolean cleared = state == AlarmState.CLEARED_UNACK || state == AlarmState.CLOSED;
        return new Alarm(a.id(), a.key(), a.instanceName(), a.severity(), a.text(), a.source(), state, a.suppressed(),
                a.raisedAt(), acked ? at : null, acked ? "supervisor1" : null, acked ? "seen" : null,
                cleared ? at : null, a.correlation());
    }

    private static AlarmEvent alarmEvent(EventType type, Instant at, Alarm a, Via via) {
        return new AlarmEvent(type, at, a, via, via == Via.OPERATOR ? "supervisor1" : null);
    }

    private static ServiceEvent service(Instant at, ServiceStatus status) {
        return new ServiceEvent(at, new ServiceState(ServiceId.SURV, status, null, false, false, List.of(), at));
    }

    @Test
    void boardAtFoldsEventsUpToAt() {
        commit(supervisor(EventType.SUPERVISOR_STARTED, T0));
        commit(equipment(EventType.EQUIPMENT_STATE_CHANGED, t(10), operational("RAD-N", t(10))));
        long command = commit(sample("RAD-N", t(12), 300),
                new CommandEvent(t(13), "RAD-N", "RESET", "supervisor1", "DONE", "c-1"));
        commit(equipment(EventType.EQUIPMENT_STATE_CHANGED, t(20), state("RAD-N", Health.FAILED, Role.STANDALONE,
                t(20), t(20))));

        ReplayBoard before = history.boardAt(t(15));

        assertThat(before.at()).isEqualTo(t(15));
        assertThat(before.board().equipment().get("RAD-N")).isEqualTo(operational("RAD-N", t(10)));
        assertThat(before.board().lastEventId()).as("the newest event at or before at, a command too")
                .isEqualTo(command);
        assertThat(before.noData()).isFalse();
        assertThat(history.boardAt(t(20)).board().equipment().get("RAD-N").health()).isEqualTo(Health.FAILED);
        assertThat(history.fold(t(15))).isEqualTo(before.board());
        assertThat(history.boardAt(NOW.plusSeconds(3600)).at()).as("an instant after now is answered for now")
                .isEqualTo(NOW);
    }

    @Test
    void foldFromASnapshotEqualsFoldFromTheStart() {
        Alarm cause = alarm("a1", "RAD-N", 101, Severity.CRITICAL, AlarmSource.DEVICE, "Equipment failed", t(20));
        Alarm correlated = alarm("a2", "RAD-N", 202, Severity.MINOR, AlarmSource.DEVICE, "Channel B degraded", t(25))
                .withCorrelation(new Correlation("a1", "RAD-N", 101, CorrelationRule.SAME_UNIT));
        Alarm closedLater = alarm("a3", "RAD-S", 203, Severity.MINOR, AlarmSource.DEVICE, "Channel C degraded", t(22));
        Alarm lost = alarm("a4", "RAD-E", AlarmCatalogue.LOST_CONTACT, AlarmCatalogue.LOST_CONTACT_SEVERITY,
                AlarmSource.SUPERVISOR, AlarmCatalogue.LOST_CONTACT_TEXT, t(70));
        commit(supervisor(EventType.SUPERVISOR_STARTED, T0));
        commit(equipment(EventType.EQUIPMENT_STATE_CHANGED, t(5), operational("RAD-N", t(5))),
                equipment(EventType.EQUIPMENT_STATE_CHANGED, t(5), operational("RAD-E", t(5))),
                equipment(EventType.EQUIPMENT_STATE_CHANGED, t(5), state("SDPS-A", Health.OPERATIONAL, Role.ACTIVE,
                        t(5), t(5))),
                service(t(5), ServiceStatus.OK));
        commit(alarmEvent(EventType.ALARM_RAISED, t(20), cause, Via.TRAP));
        commit(alarmEvent(EventType.ALARM_RAISED, t(22), closedLater, Via.TRAP));
        commit(alarmEvent(EventType.ALARM_RAISED, t(25), correlated, Via.TRAP));
        commit(sample("RAD-N", t(30), 300));
        commit(equipment(EventType.EQUIPMENT_STATE_CHANGED, t(40), state("RAD-N", Health.FAILED, Role.STANDALONE,
                t(40), t(40))), service(t(40), ServiceStatus.DEGRADED));
        commit(alarmEvent(EventType.ALARM_ACKNOWLEDGED, t(60), withState(cause, AlarmState.ACTIVE_ACK, t(60)),
                Via.OPERATOR));
        commit(new AlarmFloodEvent(EventType.ALARM_FLOOD_STARTED, t(65), 10));
        commit(equipment(EventType.CONTACT_LOST, t(70), new EquipmentState("RAD-E", Health.UNKNOWN,
                        UnknownReason.LOST_CONTACT, false, Role.STANDALONE, t(70), new Contact(false, t(55), t(70)))),
                alarmEvent(EventType.ALARM_RAISED, t(70), lost, Via.SUPERVISOR));
        commit(alarmEvent(EventType.ALARM_CLOSED, t(80), withState(closedLater, AlarmState.CLOSED, t(80)),
                Via.OPERATOR));
        commit(new CommandEvent(t(90), "SDPS-A", "SWITCHOVER", "supervisor1", "DONE", "c-1"),
                supervisor(EventType.SUPERVISOR_HEARTBEAT, t(95)));
        Board fromTheStart = history.fold(t(100));
        Board atTheSnapshot = history.fold(t(50));
        snapshots.save(atTheSnapshot, t(50));
        // the events the snapshot covers are gone, so an equal board can only come from the snapshot
        jdbc.update("delete from event_log where id <= ?", atTheSnapshot.lastEventId());

        Board fromTheSnapshot = history.fold(t(100));

        assertThat(fromTheSnapshot).isEqualTo(fromTheStart);
        assertThat(history.boardAt(t(100)).board()).isEqualTo(fromTheStart);
        assertThat(fromTheStart.openAlarms().get(correlated.key())).isEqualTo(correlated);
        assertThat(fromTheStart.openAlarms()).containsOnlyKeys(cause.key(), correlated.key(), lost.key());
        assertThat(fromTheStart.flood()).isEqualTo(new AlarmFlood(true, t(65)));
        assertThat(fromTheStart.services().get(ServiceId.SURV).status()).isEqualTo(ServiceStatus.DEGRADED);
    }

    @Test
    void boardAtLastEventIdIsTheNewestEventAtOrBeforeAt() {
        commit(supervisor(EventType.SUPERVISOR_STARTED, T0));
        long state = commit(equipment(EventType.EQUIPMENT_STATE_CHANGED, t(10), operational("RAD-N", t(10))));
        long sample = commit(sample("RAD-N", t(20), 300));
        long failed = commit(equipment(EventType.EQUIPMENT_STATE_CHANGED, t(30), state("RAD-N", Health.FAILED,
                Role.STANDALONE, t(30), t(30))));

        assertThat(history.boardAt(t(25)).board().lastEventId()).as("a sample is the newest").isEqualTo(sample);
        assertThat(history.fold(t(25)).lastEventId()).isEqualTo(sample);
        assertThat(history.boardAt(t(15)).board().lastEventId()).isEqualTo(state);
        assertThat(history.boardAt(t(30)).board().lastEventId()).isEqualTo(failed);
        assertThat(history.boardAt(T0.minusSeconds(1)).board().lastEventId()).isZero();
    }

    @Test
    void aUnitAddedToTheInventoryAppears() {
        commit(supervisor(EventType.SUPERVISOR_STARTED, T0),
                equipment(EventType.EQUIPMENT_STATE_CHANGED, t(5), operational("RAD-N", t(5))));
        Board atTheSnapshot = history.fold(t(10));
        Map<String, EquipmentState> without = new LinkedHashMap<>(atTheSnapshot.equipment());
        without.remove("FDPS-B");
        snapshots.save(new Board(without, atTheSnapshot.services(), atTheSnapshot.openAlarms(),
                atTheSnapshot.lastEventId(), atTheSnapshot.flood()), t(10));

        Board folded = history.fold(t(20));

        assertThat(folded.equipment().get("FDPS-B"))
                .isEqualTo(Board.initial(HistoryTestBeans.INVENTORY, List.of()).equipment().get("FDPS-B"));
        assertThat(folded.equipment().get("RAD-N")).isEqualTo(operational("RAD-N", t(5)));
    }

    /** OLD-1 and OLD-2 stand for units the log knows but the current inventory no longer has. */
    @Test
    void aUnitRemovedFromTheInventoryIsDropped() {
        commit(supervisor(EventType.SUPERVISOR_STARTED, T0),
                equipment(EventType.EQUIPMENT_STATE_CHANGED, t(5), operational("RAD-N", t(5))),
                equipment(EventType.EQUIPMENT_STATE_CHANGED, t(5), operational("OLD-1", t(5))));
        Board fromTheStart = history.fold(t(10));
        assertThat(fromTheStart.equipment()).as("from the start").doesNotContainKey("OLD-1")
                .containsOnlyKeys(UNITS);
        Map<String, EquipmentState> withOld = new LinkedHashMap<>(fromTheStart.equipment());
        withOld.put("OLD-1", operational("OLD-1", t(5)));
        snapshots.save(new Board(withOld, fromTheStart.services(), fromTheStart.openAlarms(),
                fromTheStart.lastEventId(), fromTheStart.flood()), t(10));
        commit(equipment(EventType.EQUIPMENT_STATE_CHANGED, t(15), operational("OLD-2", t(15))));

        Board folded = history.boardAt(t(20)).board();

        assertThat(folded.equipment()).as("from a snapshot that has one, and after an event for another")
                .doesNotContainKeys("OLD-1", "OLD-2")
                .containsOnlyKeys(UNITS);
        assertThat(folded.equipment().get("RAD-N")).isEqualTo(operational("RAD-N", t(5)));
    }

    @Test
    void boardAtDuringDowntimeIsNoData() {
        commit(supervisor(EventType.SUPERVISOR_STARTED, T0));
        commit(equipment(EventType.EQUIPMENT_STATE_CHANGED, t(5), operational("RAD-N", t(5))));
        commit(supervisor(EventType.SUPERVISOR_HEARTBEAT, t(60)));
        commit(supervisor(EventType.SUPERVISOR_STOPPING, t(100)));
        commit(supervisor(EventType.SUPERVISOR_STARTED, t(400)));
        commit(supervisor(EventType.SUPERVISOR_HEARTBEAT, t(460)));
        commit(sample("RAD-N", t(540), 300)); // a sample alone is no sign of supervision
        commit(supervisor(EventType.SUPERVISOR_STARTED, t(900))); // after a crash: no SUPERVISOR_STOPPING

        assertThat(history.boardAt(t(90)).noData()).isFalse();
        assertThat(history.boardAt(t(100)).noData()).as("a stop starts the gap at once").isTrue();
        ReplayBoard down = history.boardAt(t(300));
        assertThat(down.noData()).isTrue();
        assertThat(down.lastRecordedAt()).isEqualTo(t(100));
        assertThat(down.lastRecordedType()).isEqualTo("SUPERVISOR_STOPPING");
        assertThat(down.board()).as("the board as last recorded").isEqualTo(history.fold(t(100)));
        assertThat(history.boardAt(t(400)).noData()).isFalse();
        assertThat(history.boardAt(t(549)).noData()).isFalse();
        assertThat(history.boardAt(t(551)).noData()).as("91 s after the last heartbeat").isTrue();
        assertThat(history.boardAt(t(900)).noData()).isFalse();
    }

    @Test
    void aSampleBeforeTheRestartIsNotShownAfterIt() {
        commit(supervisor(EventType.SUPERVISOR_STARTED, T0));
        commit(sample("RAD-N", t(30), 300), sample("RAD-E", t(30), 280));
        commit(supervisor(EventType.SUPERVISOR_HEARTBEAT, t(60)));
        commit(supervisor(EventType.SUPERVISOR_STOPPING, t(70)));
        commit(supervisor(EventType.SUPERVISOR_STARTED, t(200)));
        commit(sample("RAD-N", t(230), 310));

        Map<String, Sample> beforeTheStop = history.boardAt(t(65)).samples();
        assertThat(beforeTheStop).containsOnlyKeys("RAD-N", "RAD-E");
        assertThat(beforeTheStop.get("RAD-N")).isEqualTo(new Sample("RAD-N", t(30).minusMillis(5),
                Map.of("plotsPerScan", 300L), Map.of("channels", List.of(Map.of("index", 1L, "status", "OK")))));
        assertThat(history.boardAt(t(210)).samples()).isEmpty();
        Map<String, Sample> afterTheRestart = history.boardAt(t(240)).samples();
        assertThat(afterTheRestart).containsOnlyKeys("RAD-N");
        assertThat(afterTheRestart.get("RAD-N").values()).containsEntry("plotsPerScan", 310L);
    }

    @Test
    void boardAtBeforeTheFirstEventIsInitialAndNoData() {
        commit(supervisor(EventType.SUPERVISOR_STARTED, T0));
        commit(equipment(EventType.EQUIPMENT_STATE_CHANGED, t(5), operational("RAD-N", t(5))));
        commit(sample("RAD-N", t(10), 300));

        ReplayBoard before = history.boardAt(T0.minusSeconds(1));

        assertThat(before.board()).isEqualTo(Board.initial(HistoryTestBeans.INVENTORY, List.of()));
        assertThat(before.samples()).isEmpty();
        assertThat(before.noData()).isTrue();
        assertThat(before.lastRecordedAt()).isNull();
        assertThat(before.lastRecordedType()).isNull();
    }

    /**
     * The target design §8.1 example: RAD-N is polled at 14:09:45Z, fails three probes up to 14:10:00Z and answers
     * again at 14:10:40Z; the follow-up poll then resyncs its alarm table.
     */
    @Test
    void theLostContactExampleMatchesEachRow() {
        Alarm channel = alarm("a1", "RAD-N", 201, Severity.MINOR, AlarmSource.DEVICE, "Channel A degraded",
                at("14:05:00"));
        Alarm lost = alarm("a2", "RAD-N", AlarmCatalogue.LOST_CONTACT, AlarmCatalogue.LOST_CONTACT_SEVERITY,
                AlarmSource.SUPERVISOR, AlarmCatalogue.LOST_CONTACT_TEXT, at("14:10:00"));
        commit(new SupervisorEvent(EventType.SUPERVISOR_STARTED, T0, UnknownReason.NOT_YET_POLLED));
        commit(equipment(EventType.EQUIPMENT_STATE_CHANGED, at("14:00:05"), operational("RAD-N", at("14:00:05"))));
        commit(alarmEvent(EventType.ALARM_RAISED, at("14:05:00"), channel, Via.TRAP));
        commit(supervisor(EventType.SUPERVISOR_HEARTBEAT, at("14:09:00")));
        commit(sample("RAD-N", at("14:09:45"), 300));
        commit(equipment(EventType.CONTACT_LOST, at("14:10:00"), new EquipmentState("RAD-N", Health.UNKNOWN,
                        UnknownReason.LOST_CONTACT, false, Role.STANDALONE, at("14:10:00"),
                        new Contact(false, at("14:09:45"), at("14:10:00")))),
                alarmEvent(EventType.ALARM_RAISED, at("14:10:00"), lost, Via.SUPERVISOR));
        commit(supervisor(EventType.SUPERVISOR_HEARTBEAT, at("14:10:00.500")));
        commit(equipment(EventType.CONTACT_RESTORED, at("14:10:40"), new EquipmentState("RAD-N", Health.UNKNOWN,
                        UnknownReason.AWAITING_STATUS, false, Role.STANDALONE, at("14:10:40"),
                        new Contact(true, at("14:10:40"), null))),
                alarmEvent(EventType.ALARM_CLEARED, at("14:10:40"), withState(lost, AlarmState.CLEARED_UNACK,
                        at("14:10:40")), Via.SUPERVISOR));
        commit(equipment(EventType.EQUIPMENT_STATE_CHANGED, at("14:10:40.250"),
                        operational("RAD-N", at("14:10:40.250"))),
                alarmEvent(EventType.ALARM_CLEARED, at("14:10:40.250"), withState(channel, AlarmState.CLEARED_UNACK,
                        at("14:10:40.250")), Via.RESYNC));

        ReplayBoard polled = history.boardAt(at("14:09:50"));
        assertThat(polled.noData()).isFalse();
        assertThat(polled.board().equipment().get("RAD-N")).isEqualTo(operational("RAD-N", at("14:00:05")));
        assertThat(polled.board().openAlarms()).containsOnly(Map.entry(channel.key(), channel));
        assertThat(polled.samples().get("RAD-N").observedAt()).isEqualTo(at("14:09:45").minusMillis(5));

        ReplayBoard lostContact = history.boardAt(at("14:10:05"));
        assertThat(lostContact.noData()).isFalse();
        EquipmentState unknown = lostContact.board().equipment().get("RAD-N");
        assertThat(unknown.health()).isEqualTo(Health.UNKNOWN);
        assertThat(unknown.unknownReason()).isEqualTo(UnknownReason.LOST_CONTACT);
        assertThat(unknown.healthSince()).as("UNKNOWN since 14:10:00Z").isEqualTo(at("14:10:00"));
        assertThat(unknown.contact().lastContactAt()).as("last contact 14:09:45Z").isEqualTo(at("14:09:45"));
        assertThat(lostContact.board().openAlarms()).containsOnly(Map.entry(channel.key(), channel),
                Map.entry(lost.key(), lost));
        assertThat(lostContact.samples().get("RAD-N").observedAt()).as("the last known values")
                .isEqualTo(at("14:09:45").minusMillis(5));

        ReplayBoard restored = history.boardAt(at("14:10:45"));
        assertThat(restored.noData()).isFalse();
        assertThat(restored.board().equipment().get("RAD-N"))
                .isEqualTo(operational("RAD-N", at("14:10:40.250")));
        assertThat(restored.board().openAlarms()).as("settled by the resync").containsOnly(
                Map.entry(channel.key(), withState(channel, AlarmState.CLEARED_UNACK, at("14:10:40.250"))),
                Map.entry(lost.key(), withState(lost, AlarmState.CLEARED_UNACK, at("14:10:40"))));
    }
}
