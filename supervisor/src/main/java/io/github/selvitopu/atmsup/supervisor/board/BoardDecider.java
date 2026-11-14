package io.github.selvitopu.atmsup.supervisor.board;

import static io.github.selvitopu.atmsup.supervisor.board.EventType.CONTACT_LOST;
import static io.github.selvitopu.atmsup.supervisor.board.EventType.CONTACT_RESTORED;
import static io.github.selvitopu.atmsup.supervisor.board.EventType.SUPERVISOR_HEARTBEAT;
import static io.github.selvitopu.atmsup.supervisor.board.EventType.SUPERVISOR_STARTED;
import static io.github.selvitopu.atmsup.supervisor.board.EventType.SUPERVISOR_STOPPING;

import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.supervisor.alarms.AckResult;
import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmChange;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmEngine;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmKey;
import io.github.selvitopu.atmsup.supervisor.alarms.ServiceAlarmInput;
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
            case ProbeSucceeded p -> deviceResult(live, p.probe().equipmentId(), p.generation(), null, now,
                    (cur, tel) -> deriver.probeSucceeded(cur, tel, p.probe(),
                            inv.device(p.probe().equipmentId()).pollInterval(), now));
            case ProbeFailed f -> deviceResult(live, f.equipmentId(), f.generation(), null, now,
                    (cur, tel) -> deriver.probeFailed(cur, tel, now));
            case PollSucceeded p -> deviceResult(live, p.obs().equipmentId(), p.generation(), p.obs(), now,
                    (cur, tel) -> deriver.pollSucceeded(cur, tel, p.obs(), now));
            case PollFailed f -> deviceResult(live, f.equipmentId(), f.generation(), null, now,
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
     */
    private Decision deviceResult(LiveBoard live, String id, long generation, Observation polled, Instant now,
                                  BiFunction<EquipmentState, Telemetry, StatusDeriver.Derived> derive) {
        if (generation < live.generation(id)) {
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
        if (derived.event().isPresent()) {
            EquipmentEvent ev = derived.event().get();
            boolean maintenance = ev.state().maintenance();
            w.add(ev);
            if (maintenance != cur.maintenance()) {
                w.addAlarms(alarms.onMaintenance(w.open(), id, maintenance), now);
            }
            contactChanged = ev.type() == CONTACT_LOST || ev.type() == CONTACT_RESTORED;
            if (contactChanged) {
                w.addAlarms(alarms.onContact(w.open(), id, ev.type() == CONTACT_LOST, maintenance, now), now);
            }
        }
        serviceChanges(w, now);

        Telemetry next = derived.telemetry();
        if (polled != null && sampleDue(tel, now)) {
            w.add(new TelemetrySampleEvent(now, id, polled.observedAt(), polled.values(), polled.tables()));
            next = new Telemetry(next.lastPollAt(), next.lastProbeAt(), next.consecutiveFailures(),
                    next.fullPollFailures(), now, next.values(), next.tables());
        }
        return new Decision(w.events, Map.of(id, next), List.of(), contactChanged ? Set.of(id) : Set.of(), false,
                BoardResult.ok());
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

    /** Every notification type asks for a prompt full poll and invalidates in-flight results. */
    private Decision trap(LiveBoard live, TrapReceived t, Instant now) {
        String id = t.n().equipmentId();
        EquipmentState s = live.board().equipment().get(id);
        Working w = new Working(live.board());
        w.addAlarms(alarms.onNotification(w.open(), t.n(), s != null && s.maintenance(), now), now);
        return new Decision(w.events, Map.of(), List.of(id), Set.of(id), false, BoardResult.ok());
    }

    private Decision acknowledge(LiveBoard live, AcknowledgeAlarms a, Instant now) {
        AckResult ack = alarms.acknowledge(live.board().openAlarms(), a.alarmIds(), a.actor(), a.comment(), now);
        Working w = new Working(live.board());
        w.addAlarms(ack.changes(), now);
        return new Decision(w.events, Map.of(), List.of(), Set.of(), false,
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
        return new Decision(w.events, Map.of(), List.of(), Set.of(), false, BoardResult.ok());
    }

    private static Decision supervisorOnly(SupervisorEvent e) {
        return new Decision(List.of(e), Map.of(), List.of(), Set.of(), false, BoardResult.ok());
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
