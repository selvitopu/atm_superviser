package io.github.selvitopu.atmsup.simulator.model;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.mib.AtmTsOids;
import io.github.selvitopu.atmsup.mib.EquipmentStatus;
import io.github.selvitopu.atmsup.mib.EquipmentType;
import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.simulator.agent.MibView;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.snmp4j.smi.Gauge32;

/** A flight data processing system: a flight plan count that follows a slow wave, and a message queue. */
public final class FdpsModel extends DeviceModel {

    private static final double PLAN_BASE = 450;
    private static final double PLAN_AMPLITUDE = 30;
    private static final double PLAN_PERIOD_S = 900;
    private static final long PLAN_JITTER = 10;
    private static final long QUEUE_NOMINAL = 5;
    private static final long QUEUE_NOMINAL_JITTER = 3;
    private static final long QUEUE_DEGRADED = 260;
    private static final long QUEUE_DEGRADED_JITTER = 10;
    private static final long LATENCY_MS = 150;
    private static final long LATENCY_JITTER = 40;

    /** Last time and random draws (each in [-1, 1)); scaled on read so a fault change takes effect at once. */
    private Instant valueTime;
    private double planDraw;
    private double queueDraw;
    private double latencyDraw;

    public FdpsModel(Device device, Role initialRole, Instant now) {
        super(device, initialRole, now);
        this.valueTime = now;
    }

    @Override
    public EquipmentType mibType() {
        return EquipmentType.FDPS;
    }

    @Override
    protected Set<Fault> applicableFaults() {
        return Set.of(Fault.FAIL, Fault.DEGRADE, Fault.RECOVER, Fault.UNREACHABLE, Fault.DROP_TRAPS);
    }

    @Override
    protected EquipmentStatus computeStatus() {
        if (hasFault(Fault.FAIL)) {
            return EquipmentStatus.FAILED;
        }
        return hasFault(Fault.DEGRADE) ? EquipmentStatus.DEGRADED : EquipmentStatus.OPERATIONAL;
    }

    @Override
    protected Collection<AlarmSpec> typeAlarms() {
        return hasFault(Fault.DEGRADE)
                ? List.of(new AlarmSpec(501, 0, "", Severity.MAJOR, "Message queue backlog"))
                : List.of();
    }

    @Override
    protected void updateValues(Instant now, RandomGenerator rnd) {
        valueTime = now;
        planDraw = rnd.nextDouble(-1.0, 1.0);
        queueDraw = rnd.nextDouble(-1.0, 1.0);
        latencyDraw = rnd.nextDouble(-1.0, 1.0);
    }

    private boolean failed() {
        return status() == EquipmentStatus.FAILED;
    }

    private long flightPlanCount() {
        if (failed()) {
            return 0;
        }
        double wave = PLAN_BASE + PLAN_AMPLITUDE * Math.sin(2 * Math.PI * valueTime.getEpochSecond() / PLAN_PERIOD_S);
        return Math.round(wave) + Math.round(planDraw * PLAN_JITTER);
    }

    private long queueDepth() {
        if (failed()) {
            return 0;
        }
        return hasFault(Fault.DEGRADE)
                ? QUEUE_DEGRADED + Math.round(queueDraw * QUEUE_DEGRADED_JITTER)
                : QUEUE_NOMINAL + Math.round(queueDraw * QUEUE_NOMINAL_JITTER);
    }

    private long latencyMs() {
        return failed() ? 0 : LATENCY_MS + Math.round(latencyDraw * LATENCY_JITTER);
    }

    @Override
    protected void putTypeObjects(MibView.Builder b) {
        b.put(AtmTsOids.scalar(AtmTsOids.FDPS_FLIGHT_PLAN_COUNT), new Gauge32(flightPlanCount()));
        b.put(AtmTsOids.scalar(AtmTsOids.FDPS_QUEUE_DEPTH), new Gauge32(queueDepth()));
        b.put(AtmTsOids.scalar(AtmTsOids.FDPS_LATENCY_MS), new Gauge32(latencyMs()));
    }

    @Override
    public Map<String, Object> values() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("flightPlanCount", flightPlanCount());
        values.put("queueDepth", queueDepth());
        values.put("latencyMs", latencyMs());
        return values;
    }
}
