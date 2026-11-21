package io.github.selvitopu.atmsup.supervisor.history;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmKey;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmSource;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmState;
import io.github.selvitopu.atmsup.supervisor.alarms.Correlation;
import io.github.selvitopu.atmsup.supervisor.alarms.CorrelationRule;
import io.github.selvitopu.atmsup.supervisor.alarms.Via;
import io.github.selvitopu.atmsup.supervisor.board.Board;
import io.github.selvitopu.atmsup.supervisor.board.Contact;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.AlarmEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.CommandEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.EquipmentEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.ServiceEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.SupervisorEvent;
import io.github.selvitopu.atmsup.supervisor.board.EquipmentState;
import io.github.selvitopu.atmsup.supervisor.board.EventType;
import io.github.selvitopu.atmsup.supervisor.board.Health;
import io.github.selvitopu.atmsup.supervisor.board.ServiceId;
import io.github.selvitopu.atmsup.supervisor.board.ServiceState;
import io.github.selvitopu.atmsup.supervisor.board.ServiceStatus;
import io.github.selvitopu.atmsup.supervisor.board.StoredEvent;
import io.github.selvitopu.atmsup.supervisor.board.UnknownReason;
import io.github.selvitopu.atmsup.supervisor.history.IncidentSummary.CommandRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IncidentSummaryTest {

    private static final Instant FROM = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant TO = Instant.parse("2026-01-01T12:00:00Z");
    private static final Timeline NO_MARKS = new Timeline(List.of(), false, List.of(), List.of(), List.of());

    private long nextId = 100;

    private StoredEvent stored(DomainEvent e) {
        return new StoredEvent(nextId++, e);
    }

    private static Instant at(int minutes) {
        return FROM.plusSeconds(minutes * 60L);
    }

    private static Board board(long lastEventId, Map<ServiceId, ServiceState> services,
                               Map<String, EquipmentState> equipment, List<Alarm> open) {
        Map<AlarmKey, Alarm> alarms = new LinkedHashMap<>();
        open.forEach(a -> alarms.put(a.key(), a));
        return new Board(equipment, services, alarms, lastEventId);
    }

    private static Board empty() {
        return board(0, Map.of(), Map.of(), List.of());
    }

    private static Alarm alarm(String id, String unit, int code, Severity severity, boolean suppressed,
                               AlarmState state, Correlation c) {
        return new Alarm(id, new AlarmKey(unit, code, 0), "", severity, "text " + id, AlarmSource.DEVICE, state,
                suppressed, FROM, null, null, null, null, c);
    }

    private static AlarmEvent raised(Instant when, Alarm a) {
        return new AlarmEvent(EventType.ALARM_RAISED, when, a, Via.TRAP, null);
    }

    private static ServiceEvent service(Instant when, ServiceId id, ServiceStatus status) {
        ServiceStatus best = status == ServiceStatus.UNKNOWN ? ServiceStatus.OK : null;
        return new ServiceEvent(when, new ServiceState(id, status, best, false, false, List.of(), when));
    }

    private static EquipmentState unit(String id, Health health, UnknownReason reason, Contact contact) {
        return new EquipmentState(id, health, reason, false, Role.STANDALONE, FROM, contact);
    }

    private static EquipmentEvent contact(EventType type, Instant when, String id) {
        boolean lost = type == EventType.CONTACT_LOST;
        return new EquipmentEvent(type, when, unit(id, lost ? Health.UNKNOWN : Health.OPERATIONAL,
                lost ? UnknownReason.LOST_CONTACT : null, new Contact(!lost, when, lost ? when : null)));
    }

    private static SupervisorEvent restart(Instant when) {
        return new SupervisorEvent(EventType.SUPERVISOR_STARTED, when, UnknownReason.SUPERVISOR_RESTARTED);
    }

    private static Timeline timeline(List<Timeline.Span> floods, List<Timeline.Span> gaps, List<Instant> restarts) {
        return new Timeline(List.of(), false, floods, gaps, restarts);
    }

    private IncidentSummary summarise(Board atFrom, Board atTo, List<StoredEvent> events, Timeline t) {
        return IncidentSummary.of(FROM, TO, atFrom, atTo, events, t);
    }

    @Test
    void countsRaisesBySeverityAndSuppressedApart() {
        List<StoredEvent> events = List.of(
                stored(raised(at(1), alarm("a1", "RAD-N", 101, Severity.CRITICAL, false, AlarmState.ACTIVE_UNACK, null))),
                stored(raised(at(2), alarm("a2", "RAD-E", 101, Severity.CRITICAL, false, AlarmState.ACTIVE_UNACK, null))),
                stored(raised(at(3), alarm("a3", "SDPS-A", 401, Severity.MAJOR, false, AlarmState.ACTIVE_UNACK, null))),
                stored(raised(at(4), alarm("a4", "SDPS-B", 401, Severity.MAJOR, true, AlarmState.ACTIVE_UNACK, null))));

        IncidentSummary s = summarise(empty(), empty(), events, NO_MARKS);

        assertThat(s.alarmsBySeverity()).containsEntry("CRITICAL", 2).containsEntry("MAJOR", 1)
                .containsEntry("MINOR", 0);
        assertThat(s.suppressedAlarms()).isEqualTo(1);
    }

    @Test
    void listsCausesWithTheirCorrelatedCounts() {
        Alarm cause = alarm("c1", "RAD-N", 101, Severity.CRITICAL, false, AlarmState.ACTIVE_UNACK, null);
        Alarm minor = alarm("c2", "RAD-E", 101, Severity.MINOR, false, AlarmState.ACTIVE_UNACK, null);
        Correlation toC1 = new Correlation("c1", "RAD-N", 101, CorrelationRule.SENSOR_INPUT);
        Correlation toC2 = new Correlation("c2", "RAD-E", 101, CorrelationRule.SENSOR_INPUT);
        Alarm early = alarm("d1", "RAD-N", 201, Severity.MAJOR, false, AlarmState.ACTIVE_UNACK, null);
        List<StoredEvent> events = new ArrayList<>(List.of(
                stored(raised(at(1), minor)),
                stored(raised(at(2), cause)),
                stored(raised(at(3), alarm("d1", "RAD-N", 201, Severity.MAJOR, false, AlarmState.ACTIVE_UNACK, null))),
                // d1 is linked afterwards, by an update; d2 and d3 are linked from their raise
                stored(new AlarmEvent(EventType.ALARM_UPDATED, at(4), early.withCorrelation(toC1), Via.CORRELATION,
                        null)),
                stored(raised(at(5), alarm("d2", "SDPS-A", 401, Severity.MAJOR, false, AlarmState.ACTIVE_UNACK, toC1))),
                stored(raised(at(6), alarm("d3", "SDPS-B", 401, Severity.MAJOR, false, AlarmState.ACTIVE_UNACK, toC1))),
                stored(raised(at(7), alarm("d4", "SDPS-B", 402, Severity.MAJOR, false, AlarmState.ACTIVE_UNACK, toC2))),
                // the same alarm updated again is still one alarm
                stored(new AlarmEvent(EventType.ALARM_UPDATED, at(8),
                        alarm("d3", "SDPS-B", 401, Severity.MAJOR, false, AlarmState.ACTIVE_UNACK, toC1), Via.SUPERVISOR,
                        null))));

        IncidentSummary s = summarise(empty(), empty(), events, NO_MARKS);

        assertThat(s.causes()).extracting(IncidentSummary.Cause::alarmId, IncidentSummary.Cause::equipmentId,
                        IncidentSummary.Cause::severity, IncidentSummary.Cause::correlated)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("c1", "RAD-N", "CRITICAL", 3),
                        org.assertj.core.groups.Tuple.tuple("c2", "RAD-E", "MINOR", 1));
        assertThat(s.causes().getFirst().text()).isEqualTo("text c1");
    }

    @Test
    void degradationPeriodsUseTheStateAtFrom() {
        ServiceState degradedBefore = new ServiceState(ServiceId.SURV, ServiceStatus.DEGRADED, null, false, false,
                List.of(), FROM.minusSeconds(600));
        Board atFrom = board(5, Map.of(ServiceId.SURV, degradedBefore), Map.of(), List.of());
        List<StoredEvent> events = List.of(
                stored(service(at(10), ServiceId.SURV, ServiceStatus.FAILED)),
                stored(service(at(20), ServiceId.SURV, ServiceStatus.OK)),
                stored(service(at(30), ServiceId.FD, ServiceStatus.DEGRADED)),
                stored(service(at(40), ServiceId.FD, ServiceStatus.OK)),
                stored(service(at(50), ServiceId.VOICE, ServiceStatus.DEGRADED)));

        IncidentSummary s = summarise(atFrom, empty(), events, NO_MARKS);

        assertThat(s.degradations()).hasSize(3);
        IncidentSummary.Degradation surv = s.degradations().getFirst();
        assertThat(surv.service()).isEqualTo("SURV");
        assertThat(surv.start()).isEqualTo(FROM.minusSeconds(600));
        assertThat(surv.end()).isEqualTo(at(20));
        assertThat(surv.worst()).isEqualTo("FAILED");
        assertThat(surv.durationSeconds()).isEqualTo(600 + 20 * 60);
        assertThat(surv.endedByRestart()).isFalse();
        IncidentSummary.Degradation fd = s.degradations().get(1);
        assertThat(fd).extracting(IncidentSummary.Degradation::service, IncidentSummary.Degradation::start,
                IncidentSummary.Degradation::end, IncidentSummary.Degradation::worst,
                IncidentSummary.Degradation::durationSeconds).containsExactly("FD", at(30), at(40), "DEGRADED", 600L);
        IncidentSummary.Degradation voice = s.degradations().get(2);
        assertThat(voice.service()).isEqualTo("VOICE");
        assertThat(voice.end()).isNull();
        assertThat(voice.durationSeconds()).isEqualTo(70 * 60);
    }

    @Test
    void commandsArePairedByIdAndUnpairedOnesListed() {
        List<StoredEvent> events = List.of(
                stored(new CommandEvent(at(1), "RAD-N", "RESTART", "supervisor1", "ISSUED", "c-1")),
                stored(new CommandEvent(at(2), "RAD-E", "MAINTENANCE_ON", "supervisor1", "ISSUED", "c-2")),
                stored(new CommandEvent(at(3), "RAD-N", "RESTART", "supervisor1", "DONE", "c-1")),
                stored(new CommandEvent(at(4), "RAD-E", "MAINTENANCE_ON", "supervisor1", "NO_ANSWER", "c-2")),
                // written before commands had ids: both records are listed, none paired
                stored(new CommandEvent(at(5), "SDPS-A", "MAKE_ACTIVE", "admin1", "ISSUED", null)),
                stored(new CommandEvent(at(6), "SDPS-A", "MAKE_ACTIVE", "admin1", "FAILED", null)));

        IncidentSummary s = summarise(empty(), empty(), events, NO_MARKS);

        assertThat(s.commands()).extracting(CommandRow::at, CommandRow::equipmentId, CommandRow::command,
                        CommandRow::actor, CommandRow::outcome, CommandRow::commandId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(at(1), "RAD-N", "RESTART", "supervisor1", "DONE", "c-1"),
                        org.assertj.core.groups.Tuple.tuple(at(2), "RAD-E", "MAINTENANCE_ON", "supervisor1",
                                "NO_ANSWER", "c-2"),
                        org.assertj.core.groups.Tuple.tuple(at(5), "SDPS-A", "MAKE_ACTIVE", "admin1", "ISSUED", null),
                        org.assertj.core.groups.Tuple.tuple(at(6), "SDPS-A", "MAKE_ACTIVE", "admin1", "FAILED", null));
        assertThat(s.commandCounts()).containsEntry("DONE", 1).containsEntry("NO_ANSWER", 1)
                .containsEntry("FAILED", 1).doesNotContainKey("ISSUED").doesNotContainKey("NOT_RECORDED");
    }

    @Test
    void commandsWithoutAnOutcomeAreListedAsNotRecorded() {
        List<StoredEvent> events = List.of(
                stored(new CommandEvent(at(1), "RAD-N", "RESTART", "supervisor1", "ISSUED", "c-1")),
                // an outcome whose intent came before the window is still listed
                stored(new CommandEvent(at(2), "RAD-E", "MAINTENANCE_OFF", "admin1", "REFUSED", "c-0")));

        IncidentSummary s = summarise(empty(), empty(), events, NO_MARKS);

        assertThat(s.commands()).extracting(CommandRow::equipmentId, CommandRow::outcome, CommandRow::commandId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("RAD-N", "NOT_RECORDED", "c-1"),
                        org.assertj.core.groups.Tuple.tuple("RAD-E", "REFUSED", "c-0"));
        assertThat(s.commandCounts()).containsEntry("NOT_RECORDED", 1).containsEntry("REFUSED", 1);
    }

    @Test
    void aRestartClosesOpenFloodsContactLossesAndDegradations() {
        Instant restart = at(60);
        Board atFrom = board(5, Map.of(), Map.of("FDPS-A", unit("FDPS-A", Health.UNKNOWN, UnknownReason.LOST_CONTACT,
                new Contact(false, FROM.minusSeconds(300), FROM.minusSeconds(120)))), List.of());
        List<StoredEvent> events = List.of(
                stored(service(at(10), ServiceId.NET, ServiceStatus.DEGRADED)),
                stored(contact(EventType.CONTACT_LOST, at(20), "RAD-N")),
                stored(contact(EventType.CONTACT_LOST, at(25), "RAD-E")),
                stored(contact(EventType.CONTACT_RESTORED, at(30), "RAD-E")),
                stored(restart(restart)),
                stored(service(restart, ServiceId.NET, ServiceStatus.UNKNOWN)),
                stored(contact(EventType.CONTACT_LOST, at(70), "RAD-E")));
        Timeline t = timeline(List.of(new Timeline.Span(at(15), restart), new Timeline.Span(at(80), null)),
                List.of(), List.of(restart));

        IncidentSummary s = summarise(atFrom, empty(), events, t);

        assertThat(s.floods()).extracting(IncidentSummary.Flood::start, IncidentSummary.Flood::end,
                        IncidentSummary.Flood::endedByRestart)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(at(15), restart, true),
                        org.assertj.core.groups.Tuple.tuple(at(80), null, false));
        assertThat(s.contactLosses()).extracting(IncidentSummary.ContactLoss::equipmentId,
                        IncidentSummary.ContactLoss::lostAt, IncidentSummary.ContactLoss::restoredAt,
                        IncidentSummary.ContactLoss::endedByRestart)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("FDPS-A", FROM.minusSeconds(120), restart, true),
                        org.assertj.core.groups.Tuple.tuple("RAD-N", at(20), restart, true),
                        org.assertj.core.groups.Tuple.tuple("RAD-E", at(25), at(30), false),
                        org.assertj.core.groups.Tuple.tuple("RAD-E", at(70), null, false));
        // the NET degradation the restart closed, and the one the service's own start-up event opened
        assertThat(s.degradations()).extracting(IncidentSummary.Degradation::service,
                        IncidentSummary.Degradation::end, IncidentSummary.Degradation::endedByRestart)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("NET", restart, true),
                        org.assertj.core.groups.Tuple.tuple("NET", null, false));
        assertThat(s.restarts()).containsExactly(restart);
    }

    @Test
    void gapsFollowTheGapRule() {
        Timeline.Span gap = new Timeline.Span(at(30), at(45));
        Timeline.Span open = new Timeline.Span(at(100), null);
        IncidentSummary s = summarise(empty(), empty(), List.of(), timeline(List.of(), List.of(gap, open),
                List.of(at(45))));

        assertThat(s.gaps()).containsExactly(gap, open);
        assertThat(s.restarts()).containsExactly(at(45));
    }

    @Test
    void listsTheAlarmsStillOpenAtTheEndByAcknowledgement() {
        Alarm unacked = alarm("o1", "RAD-N", 101, Severity.CRITICAL, false, AlarmState.ACTIVE_UNACK, null);
        Alarm cleared = alarm("o2", "RAD-E", 101, Severity.MAJOR, false, AlarmState.CLEARED_UNACK, null);
        Alarm acked = alarm("o3", "SDPS-A", 401, Severity.MINOR, false, AlarmState.ACTIVE_ACK, null);
        Board atTo = board(9, Map.of(), Map.of(), List.of(acked, cleared, unacked));

        IncidentSummary s = summarise(empty(), atTo, List.of(), NO_MARKS);

        assertThat(s.openUnacknowledged()).extracting(IncidentSummary.OpenAlarm::id).containsExactly("o1", "o2");
        assertThat(s.openAcknowledged()).extracting(IncidentSummary.OpenAlarm::id).containsExactly("o3");
    }

    @Test
    void eventsAlreadyFoldedIntoTheStateAtFromDoNotOpenASecondPeriod() {
        ServiceState degraded = new ServiceState(ServiceId.SURV, ServiceStatus.DEGRADED, null, false, false,
                List.of(), FROM);
        Board atFrom = board(100, Map.of(ServiceId.SURV, degraded), Map.of(), List.of());
        // id 100 is the event at FROM that the board at FROM already holds
        List<StoredEvent> events = List.of(stored(service(FROM, ServiceId.SURV, ServiceStatus.DEGRADED)));

        IncidentSummary s = summarise(atFrom, empty(), events, NO_MARKS);

        assertThat(s.degradations()).singleElement().extracting(IncidentSummary.Degradation::start)
                .isEqualTo(FROM);
    }
}
