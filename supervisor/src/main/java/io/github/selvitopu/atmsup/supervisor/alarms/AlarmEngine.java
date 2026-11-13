package io.github.selvitopu.atmsup.supervisor.alarms;

import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.supervisor.snmp.Notification;
import io.github.selvitopu.atmsup.supervisor.snmp.Notification.AlarmClearedNotification;
import io.github.selvitopu.atmsup.supervisor.snmp.Notification.AlarmRaisedNotification;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure alarm state machine. No clock, I/O or mutation of its inputs: every method takes the currently open
 * (non-CLOSED) alarms and returns the changes to apply.
 */
public final class AlarmEngine {

    private final IdGenerator ids;

    public AlarmEngine(IdGenerator ids) {
        this.ids = ids;
    }

    public List<AlarmChange> onNotification(Map<AlarmKey, Alarm> open, Notification n, boolean maintenance,
                                            Instant now) {
        return switch (n) {
            case AlarmRaisedNotification r -> raise(open, new AlarmKey(r.equipmentId(), r.code(), r.instance()),
                    r.instanceName(), r.severity(), r.text(), AlarmSource.DEVICE, Via.TRAP, maintenance, now);
            case AlarmClearedNotification c -> clear(open, new AlarmKey(c.equipmentId(), c.code(), c.instance()),
                    Via.TRAP, now);
            default -> List.of();
        };
    }

    public List<AlarmChange> onContact(Map<AlarmKey, Alarm> open, String equipmentId, boolean lost,
                                       boolean maintenance, Instant now) {
        var key = new AlarmKey(equipmentId, AlarmCatalogue.LOST_CONTACT, 0);
        if (lost) {
            return raise(open, key, "", AlarmCatalogue.LOST_CONTACT_SEVERITY, AlarmCatalogue.LOST_CONTACT_TEXT,
                    AlarmSource.SUPERVISOR, Via.SUPERVISOR, maintenance, now);
        }
        return clear(open, key, Via.SUPERVISOR, now);
    }

    /** {@code before} may be null; a null flag in {@code after} means unknown and leaves the alarm as it is. */
    public List<AlarmChange> onServiceTransition(Map<AlarmKey, Alarm> open, ServiceAlarmInput before,
                                                 ServiceAlarmInput after, Instant now) {
        var changes = new ArrayList<AlarmChange>();
        serviceFlag(open, after, after.failed(), AlarmCatalogue.SERVICE_FAILED,
                AlarmCatalogue.SERVICE_FAILED_SEVERITY, AlarmCatalogue.serviceFailedText(after.serviceName()), now,
                changes);
        serviceFlag(open, after, after.redundancyLost(), AlarmCatalogue.REDUNDANCY_LOST,
                AlarmCatalogue.REDUNDANCY_LOST_SEVERITY, AlarmCatalogue.redundancyLostText(after.serviceName()), now,
                changes);
        return changes;
    }

    public List<AlarmChange> onMaintenance(Map<AlarmKey, Alarm> open, String equipmentId, boolean maintenance) {
        var changes = new ArrayList<AlarmChange>();
        for (Alarm a : open.values()) {
            if (a.key().equipmentId().equals(equipmentId) && a.suppressed() != maintenance) {
                var updated = new Alarm(a.id(), a.key(), a.instanceName(), a.severity(), a.text(), a.source(),
                        a.state(), maintenance, a.raisedAt(), a.ackedAt(), a.ackedBy(), a.ackComment(),
                        a.clearedAt());
                changes.add(new AlarmChange(AlarmEventType.UPDATED, updated, Via.SUPERVISOR, null));
            }
        }
        return changes;
    }

    public AckResult acknowledge(Map<AlarmKey, Alarm> open, Collection<String> idsToAck, String actor,
                                 String comment, Instant now) {
        var changes = new ArrayList<AlarmChange>();
        var acknowledged = new ArrayList<Alarm>();
        var notFound = new ArrayList<String>();
        for (String id : idsToAck) {
            Alarm a = open.values().stream().filter(x -> Objects.equals(x.id(), id)).findFirst().orElse(null);
            if (a == null) {
                notFound.add(id);
                continue;
            }
            switch (a.state()) {
                case ACTIVE_UNACK -> {
                    var acked = withAck(a, AlarmState.ACTIVE_ACK, now, actor, comment);
                    changes.add(new AlarmChange(AlarmEventType.ACKNOWLEDGED, acked, Via.OPERATOR, actor));
                    acknowledged.add(acked);
                }
                case CLEARED_UNACK -> {
                    var closed = withAck(a, AlarmState.CLOSED, now, actor, comment);
                    changes.add(new AlarmChange(AlarmEventType.CLOSED, closed, Via.OPERATOR, actor));
                    acknowledged.add(closed);
                }
                default -> acknowledged.add(a);
            }
        }
        return new AckResult(changes, acknowledged, notFound);
    }

    private void serviceFlag(Map<AlarmKey, Alarm> open, ServiceAlarmInput after, Boolean flag, int code,
                             Severity severity, String text, Instant now, List<AlarmChange> out) {
        if (flag == null) {
            return;
        }
        var key = new AlarmKey(after.serviceId(), code, 0);
        if (flag) {
            out.addAll(raise(open, key, "", severity, text, AlarmSource.SUPERVISOR, Via.SUPERVISOR, false, now));
        } else {
            out.addAll(clear(open, key, Via.SUPERVISOR, now));
        }
    }

    private static Alarm withAck(Alarm a, AlarmState state, Instant now, String actor, String comment) {
        return new Alarm(a.id(), a.key(), a.instanceName(), a.severity(), a.text(), a.source(), state,
                a.suppressed(), a.raisedAt(), now, actor, comment, a.clearedAt());
    }

    private List<AlarmChange> raise(Map<AlarmKey, Alarm> open, AlarmKey key, String instanceName, Severity severity,
                                    String text, AlarmSource source, Via via, boolean maintenance, Instant now) {
        Alarm old = open.get(key);
        if (old == null) {
            var created = new Alarm(ids.next(), key, instanceName, severity, text, source, AlarmState.ACTIVE_UNACK,
                    maintenance, now, null, null, null, null);
            return List.of(new AlarmChange(AlarmEventType.RAISED, created, via, null));
        }
        if (old.state() == AlarmState.CLEARED_UNACK) {
            var reraised = new Alarm(old.id(), key, instanceName, severity, text, source, AlarmState.ACTIVE_UNACK,
                    maintenance, old.raisedAt(), null, null, null, null);
            return List.of(new AlarmChange(AlarmEventType.RAISED, reraised, via, null));
        }
        if (old.severity() == severity && Objects.equals(old.text(), text)) {
            return List.of();
        }
        boolean reUnack = severity.moreSevereThan(old.severity()) && old.state() == AlarmState.ACTIVE_ACK;
        var updated = reUnack
                ? new Alarm(old.id(), key, instanceName, severity, text, old.source(), AlarmState.ACTIVE_UNACK,
                        old.suppressed(), old.raisedAt(), null, null, null, null)
                : new Alarm(old.id(), key, instanceName, severity, text, old.source(), old.state(),
                        old.suppressed(), old.raisedAt(), old.ackedAt(), old.ackedBy(), old.ackComment(), null);
        return List.of(new AlarmChange(AlarmEventType.UPDATED, updated, via, null));
    }

    private List<AlarmChange> clear(Map<AlarmKey, Alarm> open, AlarmKey key, Via via, Instant now) {
        Alarm a = open.get(key);
        if (a == null) {
            return List.of();
        }
        return switch (a.state()) {
            case ACTIVE_UNACK -> List.of(new AlarmChange(AlarmEventType.CLEARED, withClear(a, AlarmState.CLEARED_UNACK,
                    now), via, null));
            case ACTIVE_ACK -> List.of(new AlarmChange(AlarmEventType.CLOSED, withClear(a, AlarmState.CLOSED, now),
                    via, null));
            default -> List.of();
        };
    }

    private static Alarm withClear(Alarm a, AlarmState state, Instant now) {
        return new Alarm(a.id(), a.key(), a.instanceName(), a.severity(), a.text(), a.source(), state,
                a.suppressed(), a.raisedAt(), a.ackedAt(), a.ackedBy(), a.ackComment(), now);
    }
}
