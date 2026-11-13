package io.github.selvitopu.atmsup.supervisor.board;

import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.EquipmentEvent;
import io.github.selvitopu.atmsup.supervisor.snmp.Observation;
import io.github.selvitopu.atmsup.supervisor.snmp.ProbeObservation;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Pure derivation of equipment events and new telemetry from probe and full-poll results. Only probes drive
 * contact; only full polls set health. {@code interval} is the device's poll interval.
 */
public final class StatusDeriver {

    /** Failed probes in a row that lose contact. */
    static final int LOST_AFTER = 3;

    public record Derived(Optional<EquipmentEvent> event, Telemetry telemetry) {
    }

    public Derived probeSucceeded(EquipmentState cur, Telemetry tel, ProbeObservation p, Duration interval,
                                  Instant now) {
        var newTel = new Telemetry(tel.lastPollAt(), now, 0, tel.fullPollFailures(), tel.lastSampleAt(),
                tel.values(), tel.tables());
        if (isLostContact(cur)) {
            var s = state(cur, Health.UNKNOWN, UnknownReason.AWAITING_STATUS, cur.maintenance(), cur.role(), now,
                    new Contact(true, now, null));
            return new Derived(Optional.of(new EquipmentEvent(EventType.CONTACT_RESTORED, now, s)), newTel);
        }
        boolean known = cur.health() != Health.UNKNOWN;
        boolean stale = tel.lastPollAt() == null
                || Duration.between(tel.lastPollAt(), now).compareTo(interval.multipliedBy(3)) > 0;
        if (known && stale) {
            var s = state(cur, Health.UNKNOWN, UnknownReason.STATUS_UNAVAILABLE, cur.maintenance(), cur.role(), now,
                    new Contact(true, now, null));
            return new Derived(Optional.of(new EquipmentEvent(EventType.EQUIPMENT_STATE_CHANGED, now, s)), newTel);
        }
        return new Derived(Optional.empty(), newTel);
    }

    public Derived probeFailed(EquipmentState cur, Telemetry tel, Instant now) {
        int failures = tel.consecutiveFailures() + 1;
        var newTel = new Telemetry(tel.lastPollAt(), tel.lastProbeAt(), failures, tel.fullPollFailures(),
                tel.lastSampleAt(), tel.values(), tel.tables());
        if (failures >= LOST_AFTER && !isLostContact(cur)) {
            var s = state(cur, Health.UNKNOWN, UnknownReason.LOST_CONTACT, cur.maintenance(), cur.role(), now,
                    new Contact(false, tel.lastProbeAt(), now));
            return new Derived(Optional.of(new EquipmentEvent(EventType.CONTACT_LOST, now, s)), newTel);
        }
        return new Derived(Optional.empty(), newTel);
    }

    public Derived pollSucceeded(EquipmentState cur, Telemetry tel, Observation obs, Instant now) {
        var newTel = new Telemetry(obs.observedAt(), now, 0, 0, tel.lastSampleAt(), obs.values(), obs.tables());
        Health health = Health.of(obs.status());
        Instant since = health != cur.health() ? now : cur.healthSince();
        var s = state(cur, health, null, obs.maintenance(), obs.role(), since, new Contact(true, now, null));
        if (isLostContact(cur)) {
            return new Derived(Optional.of(new EquipmentEvent(EventType.CONTACT_RESTORED, now, s)), newTel);
        }
        boolean changed = health != cur.health() || cur.unknownReason() != null
                || obs.maintenance() != cur.maintenance() || obs.role() != cur.role();
        return new Derived(changed
                ? Optional.of(new EquipmentEvent(EventType.EQUIPMENT_STATE_CHANGED, now, s))
                : Optional.empty(), newTel);
    }

    /** A failed full poll never drives contact state; probes do. */
    public Derived pollFailed(EquipmentState cur, Telemetry tel, Instant now) {
        var newTel = new Telemetry(tel.lastPollAt(), tel.lastProbeAt(), tel.consecutiveFailures(),
                tel.fullPollFailures() + 1, tel.lastSampleAt(), tel.values(), tel.tables());
        return new Derived(Optional.empty(), newTel);
    }

    private static boolean isLostContact(EquipmentState s) {
        return s.unknownReason() == UnknownReason.LOST_CONTACT;
    }

    private static EquipmentState state(EquipmentState cur, Health h, UnknownReason r, boolean maintenance,
                                        Role role, Instant since, Contact c) {
        return new EquipmentState(cur.id(), h, r, maintenance, role, since, c);
    }
}
