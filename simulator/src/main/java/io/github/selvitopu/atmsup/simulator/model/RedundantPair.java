package io.github.selvitopu.atmsup.simulator.model;

import io.github.selvitopu.atmsup.mib.EquipmentStatus;
import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.simulator.agent.SetCheck;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.Switchover;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.snmp4j.PDU;

/**
 * Two units of which one is active. The active unit hands over to the standby when it has been FAILED for three
 * seconds and the standby is healthy, or on request. Not thread-safe.
 */
public final class RedundantPair {

    private static final Duration FAILOVER_DELAY = Duration.ofSeconds(3);

    private final DeviceModel first;
    private final DeviceModel second;

    /**
     * @throws IllegalArgumentException unless {@code first} is ACTIVE and {@code second} is STANDBY
     */
    public RedundantPair(DeviceModel first, DeviceModel second) {
        if (first.role() != Role.ACTIVE || second.role() != Role.STANDBY) {
            throw new IllegalArgumentException("A pair needs an ACTIVE first unit and a STANDBY second unit");
        }
        this.first = first;
        this.second = second;
    }

    public DeviceModel active() {
        return first.role() == Role.ACTIVE ? first : second;
    }

    public DeviceModel standby() {
        return first.role() == Role.ACTIVE ? second : first;
    }

    public Optional<DeviceModel> partnerOf(String id) {
        if (first.id().equals(id)) {
            return Optional.of(second);
        }
        return second.id().equals(id) ? Optional.of(first) : Optional.empty();
    }

    /** Switches over on its own once the active unit has been FAILED long enough and the standby can take over. */
    public List<Emitted> tick(Instant now) {
        DeviceModel active = active();
        if (active.status() != EquipmentStatus.FAILED
                || now.isBefore(active.statusChangedAt().plus(FAILOVER_DELAY))) {
            return List.of();
        }
        DeviceModel standby = standby();
        boolean usable = standby.status() != EquipmentStatus.FAILED && !standby.maintenance();
        return usable ? swap(active, standby, now) : List.of();
    }

    /** Validates a request to make a unit active; the active unit already is, so it passes. */
    public SetCheck checkBecomeActive(String deviceId) {
        DeviceModel unit = unit(deviceId);
        if (unit == null) {
            return new SetCheck(PDU.inconsistentValue, "unknown device " + deviceId);
        }
        if (unit == active()) {
            return SetCheck.OK;
        }
        if (unit.status() != EquipmentStatus.OPERATIONAL) {
            return new SetCheck(PDU.inconsistentValue, "standby not operational");
        }
        if (unit.maintenance()) {
            return new SetCheck(PDU.inconsistentValue, "standby in maintenance");
        }
        return SetCheck.OK;
    }

    /**
     * Makes the unit active at once; nothing happens when it already is.
     *
     * @throws IllegalArgumentException if the device is not in this pair
     */
    public List<Emitted> becomeActive(String deviceId, Instant now) {
        DeviceModel unit = unit(deviceId);
        if (unit == null) {
            throw new IllegalArgumentException("Unknown device " + deviceId);
        }
        if (unit == active()) {
            return List.of();
        }
        return swap(active(), unit, now);
    }

    private DeviceModel unit(String id) {
        if (first.id().equals(id)) {
            return first;
        }
        return second.id().equals(id) ? second : null;
    }

    /** Both units announce their new role first, then the alarm changes follow, demoted unit first. */
    private static List<Emitted> swap(DeviceModel from, DeviceModel to, Instant now) {
        List<DeviceNotification> demoted = from.setRole(Role.STANDBY, now, true);
        List<DeviceNotification> promoted = to.setRole(Role.ACTIVE, now, true);
        List<Emitted> out = new ArrayList<>();
        out.add(new Emitted(from.id(), new Switchover(Role.STANDBY)));
        out.add(new Emitted(to.id(), new Switchover(Role.ACTIVE)));
        demoted.forEach(n -> out.add(new Emitted(from.id(), n)));
        promoted.forEach(n -> out.add(new Emitted(to.id(), n)));
        return out;
    }
}
