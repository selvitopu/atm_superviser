package io.github.selvitopu.atmsup.supervisor.history;

import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmSource;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmState;
import io.github.selvitopu.atmsup.supervisor.board.Board;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.AlarmEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.CommandEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.EquipmentEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.ServiceEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.SupervisorEvent;
import io.github.selvitopu.atmsup.supervisor.board.EquipmentState;
import io.github.selvitopu.atmsup.supervisor.board.EventType;
import io.github.selvitopu.atmsup.supervisor.board.ServiceId;
import io.github.selvitopu.atmsup.supervisor.board.ServiceState;
import io.github.selvitopu.atmsup.supervisor.board.ServiceStatus;
import io.github.selvitopu.atmsup.supervisor.board.StoredEvent;
import io.github.selvitopu.atmsup.supervisor.board.UnknownReason;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What happened in a window, for the incident export and, later, the shift handover report: the alarms raised, the
 * failures behind them, the service degradations, the commands, the floods, the contact losses, the gaps in the
 * recording and the alarms still open at the end. {@link #of} is pure: it reads only what it is given, and takes the
 * floods, gaps and restarts from the window's {@link Timeline} so that the timeline and the export agree.
 *
 * @param alarmsBySeverity   raises (new and re-raised) that were not suppressed, for every severity, worst first
 * @param suppressedAlarms   raises that were suppressed (for a unit in maintenance), counted apart
 * @param causes             the device failures (code 101) raised in the window, worst first
 * @param degradations       every period in which a service was not OK, by start
 * @param commands           every command, with its outcome or {@code NOT_RECORDED}
 * @param commandCounts      by outcome, plus the intents that have no outcome as {@code NOT_RECORDED}
 * @param floods             the alarm floods, from the timeline
 * @param contactLosses      every loss of contact with a unit, by start
 * @param gaps               the stretches nothing was recorded, from the timeline
 * @param restarts           every supervisor start, from the timeline
 * @param openUnacknowledged the alarms open at the end that nobody has acknowledged (including cleared ones)
 * @param openAcknowledged   the alarms open at the end that were acknowledged
 */
public record IncidentSummary(Map<String, Integer> alarmsBySeverity, int suppressedAlarms, List<Cause> causes,
                              List<Degradation> degradations, List<CommandRow> commands,
                              Map<String, Integer> commandCounts, List<Flood> floods,
                              List<ContactLoss> contactLosses, List<Timeline.Span> gaps, List<Instant> restarts,
                              List<OpenAlarm> openUnacknowledged, List<OpenAlarm> openAcknowledged) {

    public static final String NOT_RECORDED = "NOT_RECORDED";

    private static final String ISSUED = "ISSUED";
    private static final Comparator<Severity> WORST_FIRST = (a, b) -> a.moreSevereThan(b) ? -1
            : b.moreSevereThan(a) ? 1 : 0;

    public IncidentSummary {
        alarmsBySeverity = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(alarmsBySeverity));
        commandCounts = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(commandCounts));
        causes = List.copyOf(causes);
        degradations = List.copyOf(degradations);
        commands = List.copyOf(commands);
        floods = List.copyOf(floods);
        contactLosses = List.copyOf(contactLosses);
        gaps = List.copyOf(gaps);
        restarts = List.copyOf(restarts);
        openUnacknowledged = List.copyOf(openUnacknowledged);
        openAcknowledged = List.copyOf(openAcknowledged);
    }

    /** A device failure and how many alarms were grouped under it. */
    public record Cause(String alarmId, String equipmentId, String severity, String text, int correlated) {
    }

    /**
     * A period in which a service was not OK; {@code end} is null while it lasted to the end of the window. A period
     * already open at the window's start has {@code start} = the window's start and {@code startedBeforeFrom} true:
     * the board only knows when the service took its current status, not when it first left OK. The duration is the
     * time inside the window.
     */
    public record Degradation(String service, String label, Instant start, Instant end, String worst,
                              long durationSeconds, boolean endedByRestart, boolean startedBeforeFrom) {
    }

    /** One command; the outcome is {@code NOT_RECORDED} for an intent nothing answered. */
    public record CommandRow(Instant at, String equipmentId, String command, String actor, String outcome,
                             String commandId) {
    }

    public record Flood(Instant start, Instant end, boolean endedByRestart) {
    }

    public record ContactLoss(String equipmentId, Instant lostAt, Instant restoredAt, boolean endedByRestart) {
    }

    public record OpenAlarm(String id, String equipmentId, int code, String severity, String text, Instant raisedAt) {
    }

    /**
     * @param atFrom the board at {@code from}; with it the periods that began before the window are known
     * @param atTo   the board at {@code to}, for the alarms still open
     * @param events the window's events in id order, samples left out; those the board at {@code from} already holds
     *               (id up to its last event id) are counted but do not change a period's state
     * @param timeline the timeline of the same window: its floods, gaps and restarts are used as they are
     */
    public static IncidentSummary of(Instant from, Instant to, Board atFrom, Board atTo, List<StoredEvent> events,
                                     Timeline timeline) {
        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        severitiesWorstFirst().forEach(s -> bySeverity.put(s.name(), 0));
        int suppressed = 0;
        List<Alarm> causeAlarms = new ArrayList<>();
        Map<String, Alarm> latestPerAlarm = new LinkedHashMap<>();
        List<DomainEvent> forState = new ArrayList<>();
        for (StoredEvent stored : events) {
            DomainEvent e = stored.event();
            if (stored.id() > atFrom.lastEventId()) {
                forState.add(e);
            }
            if (e instanceof AlarmEvent a) {
                if (a.type() == EventType.ALARM_RAISED) {
                    if (a.alarm().suppressed()) {
                        suppressed++;
                    } else {
                        bySeverity.merge(a.alarm().severity().name(), 1, Integer::sum);
                    }
                    if (a.alarm().source() == AlarmSource.DEVICE && a.alarm().key().code() == 101) {
                        causeAlarms.add(a.alarm());
                    }
                }
                if (a.type() == EventType.ALARM_RAISED || a.type() == EventType.ALARM_UPDATED) {
                    latestPerAlarm.put(a.alarm().id(), a.alarm());
                }
            }
        }
        List<Instant> restarts = timeline.restarts();
        List<CommandRow> commands = new ArrayList<>();
        Map<String, Integer> commandCounts = commands(events, commands);
        return new IncidentSummary(bySeverity, suppressed, causes(causeAlarms, latestPerAlarm),
                degradations(from, to, atFrom, forState, restarts), commands, commandCounts,
                floods(timeline), contactLosses(from, atFrom, forState, restarts), timeline.gaps(), restarts,
                open(atTo, false), open(atTo, true));
    }

    private static List<Severity> severitiesWorstFirst() {
        return java.util.Arrays.stream(Severity.values()).sorted(WORST_FIRST).toList();
    }

    private static List<Cause> causes(List<Alarm> raisedCauses, Map<String, Alarm> latestPerAlarm) {
        Map<String, Integer> correlated = new LinkedHashMap<>();
        for (Alarm a : latestPerAlarm.values()) {
            if (a.correlation() != null) {
                correlated.merge(a.correlation().causeId(), 1, Integer::sum);
            }
        }
        Map<String, Alarm> distinct = new LinkedHashMap<>();
        raisedCauses.forEach(a -> distinct.put(a.id(), a));
        Comparator<Alarm> worstFirst = Comparator.comparing(Alarm::severity, WORST_FIRST);
        return distinct.values().stream()
                .sorted(worstFirst.thenComparing(a -> -correlated.getOrDefault(a.id(), 0)))
                .map(a -> new Cause(a.id(), a.key().equipmentId(), a.severity().name(), a.text(),
                        correlated.getOrDefault(a.id(), 0)))
                .toList();
    }

    /** Intent and outcome meet by command id; records without an id are listed as they are, unpaired. */
    private static Map<String, Integer> commands(List<StoredEvent> events, List<CommandRow> rows) {
        Map<String, CommandEvent> intents = new LinkedHashMap<>();
        Map<String, CommandEvent> outcomes = new LinkedHashMap<>();
        List<Object> order = new ArrayList<>(); // a command id (String), or an unpaired CommandEvent
        for (StoredEvent stored : events) {
            if (!(stored.event() instanceof CommandEvent c)) {
                continue;
            }
            if (c.commandId() == null) {
                order.add(c);
                continue;
            }
            if (!intents.containsKey(c.commandId()) && !outcomes.containsKey(c.commandId())) {
                order.add(c.commandId());
            }
            (ISSUED.equals(c.outcome()) ? intents : outcomes).put(c.commandId(), c);
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Object item : order) {
            if (item instanceof CommandEvent unpaired) {
                rows.add(row(unpaired, unpaired.outcome()));
                if (!ISSUED.equals(unpaired.outcome())) {
                    counts.merge(unpaired.outcome(), 1, Integer::sum);
                }
                continue;
            }
            CommandEvent intent = intents.get((String) item);
            CommandEvent outcome = outcomes.get((String) item);
            String result = outcome == null ? NOT_RECORDED : outcome.outcome();
            rows.add(row(intent != null ? intent : outcome, result));
            counts.merge(result, 1, Integer::sum);
        }
        return counts;
    }

    private static CommandRow row(CommandEvent c, String outcome) {
        return new CommandRow(c.occurredAt(), c.equipmentId(), c.command(), c.actor(), outcome, c.commandId());
    }

    private static List<Flood> floods(Timeline timeline) {
        return timeline.floods().stream()
                .map(s -> new Flood(s.start(), s.end(), s.end() != null && timeline.restarts().contains(s.end())))
                .toList();
    }

    private static int rank(ServiceStatus s) {
        return switch (s) {
            case OK -> 0;
            case DEGRADED -> 1;
            case UNKNOWN -> 2;
            case FAILED -> 3;
        };
    }

    private record OpenPeriod(Instant start, ServiceStatus worst, boolean beforeFrom) {
    }

    private static List<Degradation> degradations(Instant from, Instant to, Board atFrom, List<DomainEvent> events,
                                                  List<Instant> restarts) {
        Map<ServiceId, OpenPeriod> open = new EnumMap<>(ServiceId.class);
        List<Degradation> done = new ArrayList<>();
        for (ServiceState s : atFrom.services().values()) {
            if (s.status() != ServiceStatus.OK) {
                open.put(s.id(), new OpenPeriod(from, s.status(), true));
            }
        }
        for (DomainEvent e : events) {
            if (e instanceof ServiceEvent se) {
                ServiceState s = se.state();
                OpenPeriod p = open.get(s.id());
                if (s.status() == ServiceStatus.OK) {
                    if (p != null) {
                        done.add(degradation(s.id(), p, se.occurredAt(), to, restarts));
                        open.remove(s.id());
                    }
                } else if (p == null) {
                    open.put(s.id(), new OpenPeriod(se.occurredAt(), s.status(), false));
                } else if (rank(s.status()) > rank(p.worst())) {
                    open.put(s.id(), new OpenPeriod(p.start(), s.status(), p.beforeFrom()));
                }
            } else if (isRestart(e)) {
                open.forEach((id, p) -> done.add(degradation(id, p, e.occurredAt(), to, restarts)));
                open.clear();
            }
        }
        open.forEach((id, p) -> done.add(degradation(id, p, null, to, restarts)));
        done.sort(Comparator.comparing(Degradation::start));
        return done;
    }

    private static Degradation degradation(ServiceId id, OpenPeriod p, Instant end, Instant to,
                                           List<Instant> restarts) {
        long seconds = Duration.between(p.start(), end != null ? end : to).toSeconds();
        return new Degradation(id.name(), id.label(), p.start(), end, p.worst().name(), Math.max(0, seconds),
                end != null && restarts.contains(end), p.beforeFrom());
    }

    private static List<ContactLoss> contactLosses(Instant from, Board atFrom, List<DomainEvent> events,
                                                   List<Instant> restarts) {
        Map<String, Instant> open = new LinkedHashMap<>();
        List<ContactLoss> done = new ArrayList<>();
        for (EquipmentState u : atFrom.equipment().values()) {
            if (u.unknownReason() == UnknownReason.LOST_CONTACT) {
                Instant since = u.contact().lostAt() != null ? u.contact().lostAt()
                        : u.healthSince() != null ? u.healthSince() : from;
                open.put(u.id(), since);
            }
        }
        for (DomainEvent e : events) {
            if (e instanceof EquipmentEvent ee && ee.type() == EventType.CONTACT_LOST) {
                open.putIfAbsent(ee.state().id(), ee.occurredAt());
            } else if (e instanceof EquipmentEvent ee && ee.type() == EventType.CONTACT_RESTORED) {
                Instant lost = open.remove(ee.state().id());
                if (lost != null) {
                    done.add(new ContactLoss(ee.state().id(), lost, ee.occurredAt(),
                            restarts.contains(ee.occurredAt())));
                }
            } else if (isRestart(e)) {
                open.forEach((id, lost) -> done.add(new ContactLoss(id, lost, e.occurredAt(),
                        restarts.contains(e.occurredAt()))));
                open.clear();
            }
        }
        open.forEach((id, lost) -> done.add(new ContactLoss(id, lost, null, false)));
        done.sort(Comparator.comparing(ContactLoss::lostAt));
        return done;
    }

    private static boolean isRestart(DomainEvent e) {
        return e instanceof SupervisorEvent s && s.type() == EventType.SUPERVISOR_STARTED;
    }

    private static List<OpenAlarm> open(Board atTo, boolean acknowledged) {
        return atTo.openAlarms().values().stream()
                .filter(a -> acknowledged ? a.state() == AlarmState.ACTIVE_ACK
                        : a.state() == AlarmState.ACTIVE_UNACK || a.state() == AlarmState.CLEARED_UNACK)
                .sorted(Comparator.comparing(Alarm::severity, WORST_FIRST).thenComparing(Alarm::raisedAt))
                .map(a -> new OpenAlarm(a.id(), a.key().equipmentId(), a.key().code(), a.severity().name(), a.text(),
                        a.raisedAt()))
                .toList();
    }
}
