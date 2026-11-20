package io.github.selvitopu.atmsup.supervisor.history;

import static io.github.selvitopu.atmsup.mib.EquipmentStatus.FAILED;
import static io.github.selvitopu.atmsup.mib.EquipmentStatus.OPERATIONAL;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.mib.EquipmentStatus;
import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmCatalogue;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmCorrelator;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmEngine;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmKey;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmState;
import io.github.selvitopu.atmsup.supervisor.alarms.Correlation;
import io.github.selvitopu.atmsup.supervisor.alarms.CorrelationRule;
import io.github.selvitopu.atmsup.supervisor.alarms.CorrelationTopology;
import io.github.selvitopu.atmsup.supervisor.alarms.IdGenerator;
import io.github.selvitopu.atmsup.supervisor.board.AlarmFlood;
import io.github.selvitopu.atmsup.supervisor.board.AlarmFloodPolicy;
import io.github.selvitopu.atmsup.supervisor.board.Board;
import io.github.selvitopu.atmsup.supervisor.board.BoardDecider;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.AcknowledgeAlarms;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.HeartbeatTick;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.PollSucceeded;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.ProbeFailed;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.TrapReceived;
import io.github.selvitopu.atmsup.supervisor.board.BoardReducer;
import io.github.selvitopu.atmsup.supervisor.board.BoardWriter;
import io.github.selvitopu.atmsup.supervisor.board.Contact;
import io.github.selvitopu.atmsup.supervisor.board.EquipmentState;
import io.github.selvitopu.atmsup.supervisor.board.FlightDataRule;
import io.github.selvitopu.atmsup.supervisor.board.Health;
import io.github.selvitopu.atmsup.supervisor.board.ServiceEvaluator;
import io.github.selvitopu.atmsup.supervisor.board.Snapshot;
import io.github.selvitopu.atmsup.supervisor.board.SnapshotStore;
import io.github.selvitopu.atmsup.supervisor.board.StatusDeriver;
import io.github.selvitopu.atmsup.supervisor.board.StoredEvent;
import io.github.selvitopu.atmsup.supervisor.board.SurveillanceRule;
import io.github.selvitopu.atmsup.supervisor.board.UnknownReason;
import io.github.selvitopu.atmsup.supervisor.snmp.DeviceAlarm;
import io.github.selvitopu.atmsup.supervisor.snmp.Notification.AlarmRaisedNotification;
import io.github.selvitopu.atmsup.supervisor.snmp.Observation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Live equals replay: the real board writer, driven through {@code submit} with scripted traps, polls, an
 * acknowledgement and a lost contact, over the real stores. A restart is a new writer starting from the stored log
 * after the first one has shut down. Each comparison covers equipment, services, open alarms (with their
 * correlation), flood and the last event id; every script ends on an event the fold reads, so the ids must match.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({HistoryTestBeans.class, ReplayFidelityIT.ScriptedClock.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED) // the writers commit on their own thread
class ReplayFidelityIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private static final AtomicReference<Instant> NOW = new AtomicReference<>(T0);
    private static final Inventory INVENTORY = HistoryTestBeans.INVENTORY;
    private static final List<String> UNITS = INVENTORY.devices().stream().map(Device::id).toList();

    /** The stores, the history and every writer read this one scripted time. */
    @TestConfiguration
    static class ScriptedClock {
        @Bean
        InstantSource instantSource() {
            return NOW::get;
        }
    }

    @Autowired
    private JpaBoardStore store;
    @Autowired
    private SnapshotStore snapshots;
    @Autowired
    private BoardHistory history;
    @Autowired
    private EventLogRepository events;
    @Autowired
    private EventCodec codec;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<BoardWriter> writers = new ArrayList<>();

    @BeforeEach
    void emptyLog() {
        jdbc.execute("truncate event_log, alarm, board_snapshot restart identity");
        NOW.set(T0);
    }

    @AfterEach
    void stopWriters() {
        writers.stream().filter(BoardWriter::isRunning).forEach(BoardWriter::stop);
    }

    /** A writer as the application wires it, with its own publisher, clock and meters; snapshots every 5 minutes. */
    private BoardWriter newWriter() {
        var evaluator = new ServiceEvaluator(INVENTORY, List.of(new SurveillanceRule(), new FlightDataRule()));
        var decider = new BoardDecider(INVENTORY, new StatusDeriver(), evaluator, new AlarmEngine(IdGenerator.uuid()),
                new AlarmCorrelator(CorrelationTopology.from(INVENTORY)), Duration.ofSeconds(30),
                new AlarmFloodPolicy(Duration.ofMinutes(10), 3, 2));
        InstantSource clock = NOW::get;
        BoardWriter writer = new BoardWriter(INVENTORY, store, decider, clock, event -> { },
                new SimpleMeterRegistry(), Duration.ofHours(1), snapshots, Duration.ofMinutes(5));
        writers.add(writer);
        return writer;
    }

    private static Instant t(int seconds) {
        return T0.plusSeconds(seconds);
    }

    private static void at(int seconds) {
        NOW.set(t(seconds));
    }

    private static void submit(BoardWriter writer, BoardInput in) {
        assertThat(writer.submit(in).join().committed()).as("%s committed", in).isTrue();
    }

    private static PollSucceeded poll(BoardWriter writer, String id, EquipmentStatus status, DeviceAlarm... table) {
        Observation obs = new Observation(id, NOW.get(), status, writer.current().board().equipment().get(id).role(),
                false, Map.of("plotsPerScan", 300L), Map.of(), List.of(table));
        return new PollSucceeded(obs, writer.generation(id));
    }

    private static TrapReceived trap(int code, Severity severity, String text) {
        return new TrapReceived(new AlarmRaisedNotification("RAD-N", NOW.get(), code, 0, "", severity, text));
    }

    private static DeviceAlarm row(int code, Severity severity, String text) {
        return new DeviceAlarm(code, 0, "", severity, text, T0);
    }

    private static Alarm open(BoardWriter writer, String unit, int code) {
        return writer.current().board().openAlarms().get(new AlarmKey(unit, code, 0));
    }

    /**
     * Every unit polled OPERATIONAL; a RAD-N failure trap and a channel trap the correlator links to it; RAD-N
     * polled FAILED; a heartbeat 6 minutes in (the second snapshot); the failure acknowledged; RAD-E losing contact
     * after three failed probes, which also starts the flood; a closing heartbeat.
     */
    private void script(BoardWriter writer) {
        at(0);
        writer.start();
        at(5);
        UNITS.forEach(id -> submit(writer, poll(writer, id, OPERATIONAL)));
        at(20);
        submit(writer, trap(101, Severity.CRITICAL, "Equipment failed"));
        at(25);
        submit(writer, trap(202, Severity.MINOR, "Channel B degraded"));
        at(30);
        submit(writer, poll(writer, "RAD-N", FAILED, row(101, Severity.CRITICAL, "Equipment failed"),
                row(202, Severity.MINOR, "Channel B degraded")));
        at(360);
        submit(writer, new HeartbeatTick());
        at(370);
        submit(writer, new AcknowledgeAlarms(List.of(open(writer, "RAD-N", 101).id()), "seen", "supervisor1"));
        for (int s : new int[] {375, 380, 385}) {
            at(s);
            submit(writer, new ProbeFailed("RAD-E", NOW.get(), "TIMEOUT: no response",
                    writer.contactGeneration("RAD-E")));
        }
        at(400);
        submit(writer, new HeartbeatTick());
    }

    /** The oracle: every stored event in id order folded from the initial board, without snapshots or history. */
    private Board foldOfTheWholeLog() {
        Board board = Board.initial(INVENTORY, List.of());
        for (EventLogEntity row : events.findAll(Sort.by("id"))) {
            board = BoardReducer.apply(board, new StoredEvent(row.getId(), codec.decode(row)));
        }
        return board;
    }

    private static void assertSameBoard(Board actual, Board expected) {
        assertThat(actual.equipment()).as("equipment").isEqualTo(expected.equipment());
        assertThat(actual.services()).as("services").isEqualTo(expected.services());
        assertThat(actual.openAlarms()).as("open alarms, with their correlation").isEqualTo(expected.openAlarms());
        assertThat(actual.flood()).as("flood").isEqualTo(expected.flood());
        assertThat(actual.lastEventId()).as("last event id").isEqualTo(expected.lastEventId());
    }

    /** After a restart at {@code restart}: what R1 keeps (the since and the last contact) and what it clears. */
    private static void assertRestartedFromTheLog(Board board, Instant restart) {
        assertThat(board.equipment().get("RAD-N")).isEqualTo(new EquipmentState("RAD-N", Health.UNKNOWN,
                UnknownReason.SUPERVISOR_RESTARTED, false, board.equipment().get("RAD-N").role(), restart,
                new Contact(false, t(30), null)));
        assertThat(board.equipment().get("RAD-E")).as("UNKNOWN since the lost contact, last contact kept")
                .isEqualTo(new EquipmentState("RAD-E", Health.UNKNOWN, UnknownReason.SUPERVISOR_RESTARTED, false,
                        board.equipment().get("RAD-E").role(), t(385), new Contact(false, t(5), t(385))));
        Alarm cause = board.openAlarms().get(new AlarmKey("RAD-N", 101, 0));
        assertThat(cause.state()).isEqualTo(AlarmState.ACTIVE_ACK);
        assertThat(board.openAlarms().get(new AlarmKey("RAD-N", 202, 0)).correlation())
                .isEqualTo(new Correlation(cause.id(), "RAD-N", 101, CorrelationRule.SAME_UNIT));
        assertThat(board.openAlarms()).containsKey(new AlarmKey("RAD-E", AlarmCatalogue.LOST_CONTACT, 0));
        assertThat(board.flood()).as("SUPERVISOR_STARTED ends the flood").isEqualTo(AlarmFlood.NONE);
    }

    @Test
    void theLiveBoardEqualsBoardAtNowAndTheFoldFromTheStart() {
        BoardWriter writer = newWriter();
        script(writer);
        Board live = writer.current().board();
        assertThat(live.flood().active()).as("the script starts a flood").isTrue();
        assertThat(live.openAlarms().values()).as("and links an alarm to its cause")
                .anyMatch(a -> a.correlation() != null);
        assertThat(snapshots.latestAtOrBefore(NOW.get())).get().extracting(Snapshot::takenAt).isEqualTo(t(360));

        ReplayBoard now = history.boardAt(NOW.get());

        assertSameBoard(now.board(), live);
        assertSameBoard(foldOfTheWholeLog(), live);
        assertThat(now.noData()).isFalse();
        assertThat(now.samples()).containsOnlyKeys(UNITS);
    }

    @Test
    void aRestartRebuildsTheSameBoardFromTheLog() {
        script(newWriter());
        at(410);
        writers.getFirst().stop();
        at(600);
        BoardWriter restarted = newWriter();

        restarted.start();

        Board live = restarted.current().board();
        assertSameBoard(live, foldOfTheWholeLog());
        assertSameBoard(history.boardAt(NOW.get()).board(), live);
        assertRestartedFromTheLog(live, t(600));
        assertThat(history.boardAt(t(500)).noData()).as("the downtime").isTrue();
    }

    @Test
    void aStartWithoutSnapshotsFoldsTheWholeLog() {
        script(newWriter());
        at(410);
        writers.getFirst().stop();
        jdbc.update("delete from board_snapshot");
        at(600);
        assertThat(snapshots.latestAtOrBefore(NOW.get())).isEmpty();
        BoardWriter restarted = newWriter();

        restarted.start();

        Board live = restarted.current().board();
        assertSameBoard(live, foldOfTheWholeLog());
        assertRestartedFromTheLog(live, t(600));
        assertThat(snapshots.latestAtOrBefore(NOW.get())).get().extracting(Snapshot::takenAt).isEqualTo(t(600));
    }
}
