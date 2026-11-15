package io.github.selvitopu.atmsup.supervisor.board;

import static io.github.selvitopu.atmsup.supervisor.board.EventType.CONTACT_LOST;
import static io.github.selvitopu.atmsup.supervisor.board.EventType.CONTACT_RESTORED;
import static io.github.selvitopu.atmsup.supervisor.board.EventType.SUPERVISOR_HEARTBEAT;
import static io.github.selvitopu.atmsup.supervisor.board.EventType.SUPERVISOR_STARTED;
import static io.github.selvitopu.atmsup.supervisor.board.EventType.SUPERVISOR_STOPPING;

import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.supervisor.alarms.AckResult;
import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmCatalogue;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmChange;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmEngine;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmKey;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmState;
import io.github.selvitopu.atmsup.supervisor.alarms.ServiceAlarmInput;
import io.github.selvitopu.atmsup.supervisor.board.BoardEvents.RepollRequested;
import io.github.selvitopu.atmsup.supervisor.board.BoardEvents.RepollRequested.Reason;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.AcknowledgeAlarms;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.HeartbeatTick;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.PollFailed;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.PollSucceeded;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.ProbeFailed;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.ProbeSucceeded;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.Shutdown;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.Startup;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.TrapReceived;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.AlarmEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.EquipmentEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.ServiceEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.SupervisorEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.TelemetrySampleEvent;
import io.github.selvitopu.atmsup.supervisor.snmp.Observation;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Pure decision for one board input: the events to commit (each applied to a working board before the next is
 * computed), the new telemetry and the follow-ups. It has no clock, I/O or state of its own.
 */
public final class BoardDecider {

    private final Inventory inv;
    private final StatusDeriver deriver;
    private final ServiceEvaluator evaluator;
    private final AlarmEngine alarms;
    private final Duration sampleInterval;

    public BoardDecider(Inventory inv, StatusDeriver deriver, ServiceEvaluator evaluator, AlarmEngine alarms,
                        Duration telemetrySampleInterval) {
        this.inv = inv;
        this.deriver = deriver;
        this.evaluator = evaluator;
        this.alarms = alarms;
        this.sampleInterval = telemetrySampleInterval;
    }

    public Decision decide(LiveBoard live, BoardInput in, Instant now) {
        return switch (in) {
            case ProbeSucceeded p -> deviceResult(live, in, p.probe().equipmentId(), p.generation(), now,
                    (cur, tel) -> deriver.probeSucceeded(cur, tel, p.probe(),
                            inv.device(p.probe().equipmentId()).pollInterval(), now));
            case ProbeFailed f -> deviceResult(live, in, f.equipmentId(), f.generation(), now,
                    (cur, tel) -> deriver.probeFailed(cur, tel, now));
            case PollSucceeded p -> deviceResult(live, in, p.obs().equipmentId(), p.generation(), now,
                    (cur, tel) -> deriver.pollSucceeded(cur, tel, p.obs(), now));
            case PollFailed f -> deviceResult(live, in, f.equipmentId(), f.generation(), now,
                    (cur, tel) -> deriver.pollFailed(cur, tel, now));
            case TrapReceived t -> trap(live, t, now);
            case AcknowledgeAlarms a -> acknowledge(live, a, now);
            case Startup s -> startup(live, s, now);
            case HeartbeatTick h -> supervisorOnly(new SupervisorEvent(SUPERVISOR_HEARTBEAT, now, null));
            case Shutdown s -> supervisorOnly(new SupervisorEvent(SUPERVISOR_STOPPING, now, null));
        };
    }

    /**
     * A probe or poll result: freshness gate, then equipment event, maintenance updates, contact alarm, service
     * events, service alarms and (for a successful poll only) the telemetry sample, in that order.
     *
     * <p>A probe is checked against the contact generation and a full poll against the status generation. A probe
     * only proves reachability and never sets health, so a trap does not make it stale: a trapping device that stops
     * answering still reaches LOST_CONTACT. A contact change bumps both generations.
     *
     * <p>The contact alarm 901 is raised on CONTACT_LOST. Any successful probe or poll clears an ACTIVE 901, with or
     * without CONTACT_RESTORED: after a supervisor restart the device is SUPERVISOR_RESTARTED, not LOST_CONTACT, but
     * a 901 carried over the restart must still clear on the first contact. A CLEARED_UNACK 901 is left alone.
     */
    private Decision deviceResult(LiveBoard live, BoardInput in, String id, long generation, Instant now,
                                  BiFunction<EquipmentState, Telemetry, StatusDeriver.Derived> derive) {
        boolean probe = in instanceof ProbeSucceeded || in instanceof ProbeFailed;
        if (generation < (probe ? live.contactGeneration(id) : live.generation(id))) {
            return Decision.discarded();
        }
        EquipmentState cur = live.board().equipment().get(id);
        if (cur == null) {
            throw new IllegalArgumentException("Unknown equipment " + id);
        }
        Telemetry tel = live.telemetry().getOrDefault(id, Telemetry.EMPTY);
        StatusDeriver.Derived derived = derive.apply(cur, tel);

        Working w = new Working(live.board());
        boolean contactChanged = false;
        boolean maintenance = cur.maintenance();
        if (derived.event().isPresent()) {
            EquipmentEvent ev = derived.event().get();
            maintenance = ev.state().maintenance();
            w.add(ev);
            if (maintenance != cur.maintenance()) {
                w.addAlarms(alarms.onMaintenance(w.open(), id, maintenance), now);
            }
            contactChanged = ev.type() == CONTACT_LOST || ev.type() == CONTACT_RESTORED;
            if (ev.type() == CONTACT_LOST) {
                w.addAlarms(alarms.onContact(w.open(), id, true, maintenance, now), now);
            }
        }
        boolean contacted = in instanceof ProbeSucceeded || in instanceof PollSucceeded;
        if (contacted && lostContactActive(w.open(), id)) {
            w.addAlarms(alarms.onContact(w.open(), id, false, maintenance, now), now);
        }
        serviceChanges(w, now);

        Telemetry next = derived.telemetry();
        if (in instanceof PollSucceeded poll && sampleDue(tel, now)) {
            Observation polled = poll.obs();
            w.add(new TelemetrySampleEvent(now, id, polled.observedAt(), polled.values(), polled.tables()));
            next = new Telemetry(next.lastPollAt(), next.lastProbeAt(), next.consecutiveFailures(),
                    next.fullPollFailures(), now, next.values(), next.tables());
        }
        List<RepollRequested> repoll = derived.event().filter(ev -> ev.type() == CONTACT_RESTORED).isPresent()
                ? List.of(new RepollRequested(id, Reason.CONTACT_RESTORED)) : List.of();
        Set<String> invalidated = contactChanged ? Set.of(id) : Set.of();
        return new Decision(w.events, Map.of(id, next), repoll, invalidated, invalidated, false, BoardResult.ok());
    }

    private static boolean lostContactActive(Map<AlarmKey, Alarm> open, String id) {
        Alarm a = open.get(new AlarmKey(id, AlarmCatalogue.LOST_CONTACT, 0));
        return a != null && (a.state() == AlarmState.ACTIVE_UNACK || a.state() == AlarmState.ACTIVE_ACK);
    }

    /** No deadband: a stable value is sampled too (amendment §5). */
    private boolean sampleDue(Telemetry tel, Instant now) {
        return tel.lastSampleAt() == null || Duration.between(tel.lastSampleAt(), now).compareTo(sampleInterval) >= 0;
    }

    /** Service events for every service whose status, best case, redundancy or reasons changed, then their alarms. */
    private void serviceChanges(Working w, Instant now) {
        Map<ServiceId, ServiceState> before = w.board.services();
        Map<ServiceId, ServiceState> changed = new EnumMap<>(ServiceId.class);
        evaluator.evaluate(w.board, now).forEach((id, s) -> {
            if (differs(before.get(id), s)) {
                changed.put(id, s);
            }
        });
        changed.values().forEach(s -> w.add(new ServiceEvent(now, s)));
        changed.forEach((id, s) -> w.addAlarms(
                alarms.onServiceTransition(w.open(), alarmInput(before.get(id)), alarmInput(s), now), now));
    }

    private static boolean differs(ServiceState prev, ServiceState s) {
        return prev == null || prev.status() != s.status() || !Objects.equals(prev.bestCase(), s.bestCase())
                || prev.redundancyLost() != s.redundancyLost() || !prev.reasons().equals(s.reasons());
    }

    /** UNKNOWN leaves both flags null, so the service alarms stay as they are. */
    private static ServiceAlarmInput alarmInput(ServiceState s) {
        if (s == null) {
            return null;
        }
        boolean unknown = s.status() == ServiceStatus.UNKNOWN;
        return new ServiceAlarmInput(s.id().name(), s.id().label(),
                unknown ? null : s.status() == ServiceStatus.FAILED, unknown ? null : s.redundancyLost());
    }

    /**
     * Every notification type asks for a prompt full poll and invalidates in-flight full polls (the status generation
     * only: an in-flight probe is still valid evidence about contact).
     */
    private Decision trap(LiveBoard live, TrapReceived t, Instant now) {
        String id = t.n().equipmentId();
        EquipmentState s = live.board().equipment().get(id);
        Working w = new Working(live.board());
        w.addAlarms(alarms.onNotification(w.open(), t.n(), s != null && s.maintenance(), now), now);
        return new Decision(w.events, Map.of(), List.of(new RepollRequested(id, Reason.TRAP)), Set.of(id), Set.of(),
                false, BoardResult.ok());
    }

    private Decision acknowledge(LiveBoard live, AcknowledgeAlarms a, Instant now) {
        AckResult ack = alarms.acknowledge(live.board().openAlarms(), a.alarmIds(), a.actor(), a.comment(), now);
        Working w = new Working(live.board());
        w.addAlarms(ack.changes(), now);
        return new Decision(w.events, Map.of(), List.of(), Set.of(), Set.of(), false,
                new BoardResult(true, ack.acknowledged(), ack.notFound(), null));
    }

    /** SUPERVISOR_STARTED, then the state of every service on the resulting all-UNKNOWN board. */
    private Decision startup(LiveBoard live, Startup s, Instant now) {
        Working w = new Working(live.board());
        w.add(new SupervisorEvent(SUPERVISOR_STARTED, now,
                s.firstStart() ? UnknownReason.NOT_YET_POLLED : UnknownReason.SUPERVISOR_RESTARTED));
        Map<ServiceId, ServiceState> services = new EnumMap<>(ServiceId.class);
        services.putAll(evaluator.evaluate(w.board, now));
        services.values().forEach(st -> w.add(new ServiceEvent(now, st)));
        return new Decision(w.events, Map.of(), List.of(), Set.of(), Set.of(), false, BoardResult.ok());
    }

    private static Decision supervisorOnly(SupervisorEvent e) {
        return new Decision(List.of(e), Map.of(), List.of(), Set.of(), Set.of(), false, BoardResult.ok());
    }

    /** The events of one decision, each applied to a working board before the next one is computed. */
    private static final class Working {

        private final List<DomainEvent> events = new ArrayList<>();
        private Board board;

        Working(Board board) {
            this.board = board;
        }

        void add(DomainEvent e) {
            events.add(e);
            board = BoardReducer.apply(board, e);
        }

        void addAlarms(List<AlarmChange> changes, Instant now) {
            for (AlarmChange c : changes) {
                add(new AlarmEvent(EventType.ofAlarm(c.type()), now, c.alarm(), c.via(), c.actor()));
            }
        }

        Map<AlarmKey, Alarm> open() {
            return board.openAlarms();
        }
    }
}
