package io.github.selvitopu.atmsup.supervisor.alarms;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure correlation rules: decides which failure ({@code DEVICE} 101 alarm) an alarm is grouped under. No clock, I/O
 * or mutation of its inputs. An alarm's rule comes from its shape alone; whether its cause is open only decides
 * whether a link is made. A correlated alarm is never more severe than its cause.
 */
public final class AlarmCorrelator {

    private static final int FAILURE = 101;
    private static final int SWITCHOVER_CODE = 104;
    private static final int SENSOR_INPUT_CODE = 401;
    private static final int MAINTENANCE = 103;

    private static final Comparator<Alarm> BY_KEY = Comparator
            .comparing((Alarm a) -> a.key().equipmentId())
            .thenComparingInt(a -> a.key().code())
            .thenComparingInt(a -> a.key().instance());

    private final CorrelationTopology topology;

    public AlarmCorrelator(CorrelationTopology topology) {
        this.topology = topology;
    }

    /** A rule and the unit whose failure it names. */
    private record Shape(CorrelationRule rule, String unit) {
    }

    /**
     * Returns {@code change} as it must be applied, followed by the adoption or detachment changes it causes
     * ({@code UPDATED}, {@link Via#CORRELATION}, no actor) in (equipment id, code, instance) order. {@code open}
     * is the board's open alarms before {@code change}.
     */
    public List<AlarmChange> apply(Map<AlarmKey, Alarm> open, AlarmChange change) {
        Alarm a = change.alarm();
        if (change.type() == AlarmEventType.RAISED) {
            return raised(open, change, a);
        }
        Alarm current = open.get(a.key());
        if (current == null || !Objects.equals(current.id(), a.id())) {
            return List.of(change);
        }
        Alarm carried = a.withCorrelation(current.correlation());
        var out = new ArrayList<AlarmChange>();
        if (change.type() == AlarmEventType.UPDATED && a.severity() != current.severity()) {
            Correlation link = carried.correlation();
            if (link != null) {
                Alarm cause = byId(open, link.causeId());
                if (cause != null && carried.severity().moreSevereThan(cause.severity())) {
                    carried = carried.withCorrelation(null);
                }
            }
            if (isFailure(carried)) {
                detachMoreSevere(open, carried).forEach(o -> out.add(followUp(o)));
            }
        }
        out.add(0, new AlarmChange(change.type(), carried, change.via(), change.actor()));
        return out;
    }

    private List<AlarmChange> raised(Map<AlarmKey, Alarm> open, AlarmChange change, Alarm a) {
        Alarm linked = a.withCorrelation(linkFor(open, a));
        var out = new ArrayList<AlarmChange>();
        out.add(new AlarmChange(change.type(), linked, change.via(), change.actor()));
        if (isFailure(a) && active(a)) {
            var followUps = new ArrayList<Alarm>(detachMoreSevere(open, a));
            for (Alarm o : sorted(open)) {
                if (o.key().equals(a.key()) || o.source() != AlarmSource.DEVICE || !active(o)) {
                    continue;
                }
                Shape shape = shapeOf(o);
                if (shape == null || !shape.unit().equals(a.key().equipmentId())
                        || o.severity().moreSevereThan(a.severity())) {
                    continue;
                }
                Correlation old = o.correlation();
                if (old != null && (old.causeId().equals(a.id()) || isActiveFailure(byId(open, old.causeId())))) {
                    continue;
                }
                followUps.add(o.withCorrelation(new Correlation(a.id(), a.key().equipmentId(), FAILURE,
                        shape.rule())));
            }
            followUps.sort(BY_KEY);
            followUps.forEach(o -> out.add(followUp(o)));
        }
        return out;
    }

    /** The open alarms correlated to {@code cause} that are more severe than it, detached, in key order. */
    private static List<Alarm> detachMoreSevere(Map<AlarmKey, Alarm> open, Alarm cause) {
        var detached = new ArrayList<Alarm>();
        for (Alarm o : sorted(open)) {
            if (o.correlation() != null && o.correlation().causeId().equals(cause.id())
                    && o.severity().moreSevereThan(cause.severity())) {
                detached.add(o.withCorrelation(null));
            }
        }
        return detached;
    }

    private Correlation linkFor(Map<AlarmKey, Alarm> open, Alarm a) {
        Shape shape = shapeOf(a);
        if (shape == null) {
            return null;
        }
        Alarm cause = open.get(new AlarmKey(shape.unit(), FAILURE, 0));
        if (cause == null || !isActiveFailure(cause) || a.severity().moreSevereThan(cause.severity())) {
            return null;
        }
        return new Correlation(cause.id(), cause.key().equipmentId(), FAILURE, shape.rule());
    }

    /** The first matching row of the shape table, or null for an alarm that is never correlated. */
    private Shape shapeOf(Alarm a) {
        if (a.source() != AlarmSource.DEVICE) {
            return null;
        }
        int code = a.key().code();
        String unit = a.key().equipmentId();
        if (code == SENSOR_INPUT_CODE && a.instanceName() != null && topology.sensors().contains(a.instanceName())) {
            return new Shape(CorrelationRule.SENSOR_INPUT, a.instanceName());
        }
        if (code == SWITCHOVER_CODE && topology.partners().containsKey(unit)) {
            return new Shape(CorrelationRule.SWITCHOVER, topology.partners().get(unit));
        }
        if (code != FAILURE && code != MAINTENANCE) {
            return new Shape(CorrelationRule.SAME_UNIT, unit);
        }
        return null;
    }

    private static boolean isFailure(Alarm a) {
        return a.source() == AlarmSource.DEVICE && a.key().code() == FAILURE
                && a.key().instance() == 0;
    }

    private static boolean active(Alarm a) {
        return a.state() == AlarmState.ACTIVE_UNACK || a.state() == AlarmState.ACTIVE_ACK;
    }

    private static boolean isActiveFailure(Alarm a) {
        return a != null && isFailure(a) && active(a);
    }

    private static Alarm byId(Map<AlarmKey, Alarm> open, String id) {
        for (Alarm o : open.values()) {
            if (Objects.equals(o.id(), id)) {
                return o;
            }
        }
        return null;
    }

    private static List<Alarm> sorted(Map<AlarmKey, Alarm> open) {
        return open.values().stream().sorted(BY_KEY).toList();
    }

    private static AlarmChange followUp(Alarm a) {
        return new AlarmChange(AlarmEventType.UPDATED, a, Via.CORRELATION, null);
    }
}
