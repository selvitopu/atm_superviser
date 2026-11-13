package io.github.selvitopu.atmsup.simulator.model;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.mib.AtmTsOids;
import io.github.selvitopu.atmsup.mib.CommandResult;
import io.github.selvitopu.atmsup.mib.DateAndTimes;
import io.github.selvitopu.atmsup.mib.EquipmentStatus;
import io.github.selvitopu.atmsup.mib.EquipmentType;
import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.simulator.agent.MibView;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.StatusChange;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;

/**
 * In-memory state of one simulated device. A model owns its status, role, faults and alarm table, produces the
 * {@link MibView} the agent serves, and returns the {@link DeviceNotification}s each change causes. It is not
 * thread-safe: callers serialise access.
 *
 * <p>Subclasses supply the type specifics through the abstract hooks; every state change ends in {@link #recompute}.
 */
public abstract class DeviceModel {

    static final String SW_VERSION = "demo-1.0.0";
    private static final Duration SWITCHOVER_ALARM_DURATION = Duration.ofSeconds(60);

    private final Device device;
    private final Instant agentStartedAt;
    private final Map<Fault, String> faults = new EnumMap<>(Fault.class);
    private final AlarmTable alarmTable = new AlarmTable();
    private Role role;
    private EquipmentStatus status;
    private Instant statusChangedAt;
    private boolean maintenance;
    private Instant switchoverAt;
    private CommandResult commandResult = CommandResult.NONE;

    protected DeviceModel(Device device, Role initialRole, Instant now) {
        this.device = device;
        this.role = initialRole;
        this.agentStartedAt = now;
        this.statusChangedAt = now;
        this.status = computeStatus();
    }

    // ---- hooks for the concrete model ----

    public abstract EquipmentType mibType();

    protected abstract Set<Fault> applicableFaults();

    /** The health the current faults and values imply. */
    protected abstract EquipmentStatus computeStatus();

    /** Alarms specific to the type that should be active now. */
    protected abstract Collection<AlarmSpec> typeAlarms();

    /** Moves the simulated measurements one step; {@link #recompute} runs afterwards. */
    protected abstract void updateValues(Instant now, RandomGenerator rnd);

    /** Adds the type's objects (radar, SDPS, ...) to the view. */
    protected abstract void putTypeObjects(MibView.Builder b);

    /** The measurements for the console, keyed by name; numbers are Long, enums are their names. */
    public abstract Map<String, Object> values();

    // ---- getters ----

    public String id() {
        return device.id();
    }

    public Device device() {
        return device;
    }

    public EquipmentStatus status() {
        return status;
    }

    public Instant statusChangedAt() {
        return statusChangedAt;
    }

    public Role role() {
        return role;
    }

    public boolean maintenance() {
        return maintenance;
    }

    /** The active faults, from fault to target (the target may be null). */
    public Map<Fault, String> faults() {
        return Collections.unmodifiableMap(new EnumMap<>(faults));
    }

    public boolean unreachable() {
        return faults.containsKey(Fault.UNREACHABLE);
    }

    public boolean dropTraps() {
        return faults.containsKey(Fault.DROP_TRAPS);
    }

    public List<ActiveAlarm> alarms() {
        return alarmTable.active();
    }

    public CommandResult commandResult() {
        return commandResult;
    }

    public Instant agentStartedAt() {
        return agentStartedAt;
    }

    protected boolean hasFault(Fault fault) {
        return faults.containsKey(fault);
    }

    // ---- mutators ----

    /**
     * Applies a fault. {@code RECOVER} clears every fault, including UNREACHABLE and DROP_TRAPS.
     *
     * @throws IllegalArgumentException if the fault does not apply to this type, or needs a target that is missing
     */
    public List<DeviceNotification> applyFault(FaultRequest r, Instant now) {
        Fault fault = r.fault();
        if (!applicableFaults().contains(fault)) {
            throw new IllegalArgumentException("Fault " + fault + " not applicable to " + mibType());
        }
        if (fault == Fault.SENSOR_LOST && r.target() == null) {
            throw new IllegalArgumentException("Fault SENSOR_LOST needs a target");
        }
        if (fault == Fault.RECOVER) {
            faults.clear();
        } else {
            faults.put(fault, r.target());
        }
        return recompute(now);
    }

    /** Advances the measurements, then recomputes status and alarms (which also expires the switchover alarm). */
    public List<DeviceNotification> tick(Instant now, RandomGenerator rnd) {
        updateValues(now, rnd);
        return recompute(now);
    }

    public List<DeviceNotification> setRole(Role r, Instant now) {
        return setRole(r, now, false);
    }

    /**
     * Sets the role. Becoming ACTIVE because of a switchover raises alarm 104 for 60 seconds; a demotion to STANDBY
     * ends it, since 104 belongs to the unit that is active now; any other change leaves it as it is.
     */
    public List<DeviceNotification> setRole(Role r, Instant now, boolean bySwitchover) {
        this.role = r;
        if (r == Role.ACTIVE && bySwitchover) {
            this.switchoverAt = now;
        } else if (r == Role.STANDBY) {
            this.switchoverAt = null;
        }
        return recompute(now);
    }

    /** Switches maintenance mode, which holds alarm 103 while on. */
    public List<DeviceNotification> setMaintenance(boolean on, Instant now) {
        this.maintenance = on;
        return recompute(now);
    }

    protected void setCommandResult(CommandResult result) {
        this.commandResult = result;
    }

    /**
     * Recomputes status and alarms. A status change comes first as a {@link StatusChange}, then the alarm clears
     * and raises.
     */
    protected List<DeviceNotification> recompute(Instant now) {
        List<DeviceNotification> out = new ArrayList<>();
        EquipmentStatus computed = computeStatus();
        if (computed != status) {
            status = computed;
            statusChangedAt = now;
            out.add(new StatusChange(status, role));
        }
        List<AlarmSpec> desired = new ArrayList<>(commonAlarms(now));
        desired.addAll(typeAlarms());
        out.addAll(alarmTable.reconcile(desired, now));
        return out;
    }

    private List<AlarmSpec> commonAlarms(Instant now) {
        List<AlarmSpec> specs = new ArrayList<>();
        if (status == EquipmentStatus.FAILED) {
            specs.add(new AlarmSpec(101, 0, "", Severity.CRITICAL, "Equipment failed"));
        }
        if (status == EquipmentStatus.DEGRADED) {
            specs.add(new AlarmSpec(102, 0, "", Severity.MINOR, "Equipment degraded"));
        }
        if (maintenance) {
            specs.add(new AlarmSpec(103, 0, "", Severity.WARNING, "Maintenance mode on"));
        }
        if (switchoverAt != null && now.isBefore(switchoverAt.plus(SWITCHOVER_ALARM_DURATION))) {
            specs.add(new AlarmSpec(104, 0, "", Severity.WARNING, "Switchover occurred"));
        }
        return specs;
    }

    // ---- view ----

    /** A fresh snapshot of everything the agent serves; {@code now} fixes sysUpTime. */
    public MibView view(Instant now) {
        MibView.Builder b = MibView.builder();
        long centis = Math.max(0, Duration.between(agentStartedAt, now).toMillis() / 10);
        b.put(SnmpConstants.sysDescr, new OctetString("Demo ACC simulated " + mibType() + " " + id()));
        b.put(SnmpConstants.sysObjectID, AtmTsOids.ROOT);
        b.put(SnmpConstants.sysUpTime, new TimeTicks(centis));
        b.put(SnmpConstants.sysName, new OctetString(id()));

        b.put(AtmTsOids.scalar(AtmTsOids.EQ_ID), new OctetString(id()));
        b.put(AtmTsOids.scalar(AtmTsOids.EQ_TYPE), new Integer32(mibType().code()));
        b.put(AtmTsOids.scalar(AtmTsOids.EQ_STATUS), new Integer32(status.code()));
        b.put(AtmTsOids.scalar(AtmTsOids.EQ_ROLE), new Integer32(role.code()));
        b.put(AtmTsOids.scalar(AtmTsOids.EQ_STATUS_CHANGED_AT), DateAndTimes.encode(statusChangedAt));
        b.put(AtmTsOids.scalar(AtmTsOids.EQ_SW_VERSION), new OctetString(SW_VERSION));
        b.put(AtmTsOids.scalar(AtmTsOids.EQ_MAINTENANCE_MODE), new Integer32(maintenance ? 1 : 2));
        b.put(AtmTsOids.scalar(AtmTsOids.EQ_COMMAND), new Integer32(1));
        b.put(AtmTsOids.scalar(AtmTsOids.EQ_LAST_COMMAND_RESULT), new Integer32(commandResult.code()));

        // atmTsAlarmCode and atmTsAlarmInstance are the table index, so they are not-accessible and not served.
        for (ActiveAlarm a : alarmTable.active()) {
            b.put(row(AtmTsOids.ALARM_INSTANCE_NAME, a), new OctetString(a.instanceName()));
            b.put(row(AtmTsOids.ALARM_SEVERITY, a), new Integer32(a.severity().code()));
            b.put(row(AtmTsOids.ALARM_TEXT, a), new OctetString(a.text()));
            b.put(row(AtmTsOids.ALARM_RAISED_AT, a), DateAndTimes.encode(a.raisedAt()));
        }
        putTypeObjects(b);
        return b.build();
    }

    private static OID row(OID column, ActiveAlarm alarm) {
        return new OID(column).append(alarm.code()).append(alarm.instance());
    }
}
