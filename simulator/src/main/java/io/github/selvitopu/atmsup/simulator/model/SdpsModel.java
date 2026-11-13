package io.github.selvitopu.atmsup.simulator.model;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.mib.AtmTsOids;
import io.github.selvitopu.atmsup.mib.EquipmentStatus;
import io.github.selvitopu.atmsup.mib.EquipmentType;
import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.mib.SensorInputStatus;
import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.simulator.agent.MibView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.snmp4j.smi.Gauge32;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;

/**
 * A surveillance data processing system fed by named sensors. A lost sensor lowers the track count and raises a
 * minor alarm, but does not degrade the system by itself.
 */
public final class SdpsModel extends DeviceModel {

    private static final double TRACK_BASE = 120;
    private static final double TRACK_AMPLITUDE = 30;
    private static final double TRACK_PERIOD_S = 600;
    private static final long TRACK_JITTER = 5;
    private static final long LATENCY_NOMINAL = 80;
    private static final long LATENCY_NOMINAL_JITTER = 20;
    private static final long LATENCY_DEGRADED = 650;
    private static final long LATENCY_DEGRADED_JITTER = 30;

    /** One row of the sensor table; the index starts at 1. */
    public record SensorRow(int index, String name, SensorInputStatus status) {
    }

    private final List<String> sensorNames;
    private final Map<String, Boolean> feedOk = new HashMap<>();

    /** Last time and random draws (each in [-1, 1)); scaled on read so a change takes effect at once. */
    private Instant valueTime;
    private double trackDraw;
    private double latencyDraw;

    public SdpsModel(Device device, Role initialRole, Instant now, List<String> sensorNames) {
        super(device, initialRole, now);
        this.sensorNames = List.copyOf(sensorNames);
        this.valueTime = now;
    }

    @Override
    public EquipmentType mibType() {
        return EquipmentType.SDPS;
    }

    @Override
    protected Set<Fault> applicableFaults() {
        return Set.of(Fault.FAIL, Fault.DEGRADE, Fault.RECOVER, Fault.SENSOR_LOST, Fault.UNREACHABLE,
                Fault.DROP_TRAPS);
    }

    @Override
    public List<DeviceNotification> applyFault(FaultRequest r, Instant now) {
        if (r.fault() == Fault.SENSOR_LOST && r.target() != null && !sensorNames.contains(r.target())) {
            throw new IllegalArgumentException("Unknown sensor " + r.target());
        }
        return super.applyFault(r, now);
    }

    /** Records which sensor feeds are healthy (a missing name counts as healthy) and recomputes the alarms. */
    public List<DeviceNotification> updateFeeds(Map<String, Boolean> feeds, Instant now) {
        feedOk.clear();
        feedOk.putAll(feeds);
        return recompute(now);
    }

    /** The sensor table as currently served. */
    public List<SensorRow> sensors() {
        String lostByFault = faults().get(Fault.SENSOR_LOST);
        List<SensorRow> rows = new ArrayList<>();
        for (int i = 0; i < sensorNames.size(); i++) {
            String name = sensorNames.get(i);
            boolean lost = !feedOk.getOrDefault(name, true) || name.equals(lostByFault);
            rows.add(new SensorRow(i + 1, name, lost ? SensorInputStatus.LOST : SensorInputStatus.RECEIVING));
        }
        return rows;
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
        List<AlarmSpec> specs = new ArrayList<>();
        for (SensorRow row : sensors()) {
            if (row.status() == SensorInputStatus.LOST) {
                specs.add(new AlarmSpec(401, row.index(), row.name(), Severity.MINOR, "Sensor input lost"));
            }
        }
        if (hasFault(Fault.DEGRADE)) {
            specs.add(new AlarmSpec(402, 0, "", Severity.MAJOR, "Processing latency high"));
        }
        return specs;
    }

    @Override
    protected void updateValues(Instant now, RandomGenerator rnd) {
        valueTime = now;
        trackDraw = rnd.nextDouble(-1.0, 1.0);
        latencyDraw = rnd.nextDouble(-1.0, 1.0);
    }

    private boolean failed() {
        return status() == EquipmentStatus.FAILED;
    }

    private long trackCount() {
        if (failed() || sensorNames.isEmpty()) {
            return 0;
        }
        long receiving = sensors().stream().filter(r -> r.status() == SensorInputStatus.RECEIVING).count();
        double fraction = (double) receiving / sensorNames.size();
        double wave = TRACK_BASE
                + TRACK_AMPLITUDE * Math.sin(2 * Math.PI * valueTime.getEpochSecond() / TRACK_PERIOD_S);
        return Math.max(0, Math.round(wave * fraction) + Math.round(trackDraw * TRACK_JITTER));
    }

    private long latencyMs() {
        if (failed()) {
            return 0;
        }
        return hasFault(Fault.DEGRADE)
                ? LATENCY_DEGRADED + Math.round(latencyDraw * LATENCY_DEGRADED_JITTER)
                : LATENCY_NOMINAL + Math.round(latencyDraw * LATENCY_NOMINAL_JITTER);
    }

    @Override
    protected void putTypeObjects(MibView.Builder b) {
        b.put(AtmTsOids.scalar(AtmTsOids.SDPS_TRACK_COUNT), new Gauge32(trackCount()));
        b.put(AtmTsOids.scalar(AtmTsOids.SDPS_LATENCY_MS), new Gauge32(latencyMs()));
        // The sensor index is the table index (not-accessible), so only the name and the status are served.
        for (SensorRow row : sensors()) {
            b.put(new OID(AtmTsOids.SDPS_SENSOR_NAME).append(row.index()), new OctetString(row.name()));
            b.put(new OID(AtmTsOids.SDPS_SENSOR_INPUT_STATUS).append(row.index()),
                    new Integer32(row.status().code()));
        }
    }

    @Override
    public Map<String, Object> values() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("trackCount", trackCount());
        values.put("latencyMs", latencyMs());
        return values;
    }
}
