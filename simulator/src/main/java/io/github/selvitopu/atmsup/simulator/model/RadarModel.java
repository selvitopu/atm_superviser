package io.github.selvitopu.atmsup.simulator.model;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.mib.ActiveChannel;
import io.github.selvitopu.atmsup.mib.AtmTsOids;
import io.github.selvitopu.atmsup.mib.EquipmentStatus;
import io.github.selvitopu.atmsup.mib.EquipmentType;
import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.simulator.agent.MibView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.snmp4j.smi.Gauge32;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.UnsignedInteger32;

/** A dual-channel surveillance radar: one channel fails over to the other, both failing fails the radar. */
public final class RadarModel extends DeviceModel {

    private static final long NOMINAL_ROTATION_MS = 4400;
    private static final long ROTATION_JITTER_MS = 150;
    private static final long NOMINAL_PLOTS = 300;
    private static final long NOMINAL_PLOTS_JITTER = 20;
    private static final long DEGRADED_PLOTS = 120;
    private static final long DEGRADED_PLOTS_JITTER = 10;

    /** Last random draws, each in [-1, 1); scaled by the current jitter so a fault change takes effect at once. */
    private double rotationDraw;
    private double plotsDraw;

    public RadarModel(Device device, Instant now) {
        super(device, Role.STANDALONE, now);
    }

    @Override
    public EquipmentType mibType() {
        return EquipmentType.RADAR;
    }

    @Override
    protected Set<Fault> applicableFaults() {
        return Set.of(Fault.FAIL, Fault.DEGRADE, Fault.RECOVER, Fault.CHANNEL_A_FAIL, Fault.CHANNEL_B_FAIL,
                Fault.UNREACHABLE, Fault.DROP_TRAPS);
    }

    private boolean channelAFailed() {
        return hasFault(Fault.CHANNEL_A_FAIL) || hasFault(Fault.FAIL);
    }

    private boolean channelBFailed() {
        return hasFault(Fault.CHANNEL_B_FAIL) || hasFault(Fault.FAIL);
    }

    private ActiveChannel activeChannel() {
        return channelAFailed() && !channelBFailed() ? ActiveChannel.CHANNEL_B : ActiveChannel.CHANNEL_A;
    }

    @Override
    protected EquipmentStatus computeStatus() {
        if (channelAFailed() && channelBFailed()) {
            return EquipmentStatus.FAILED;
        }
        if (channelAFailed() || channelBFailed() || hasFault(Fault.DEGRADE)) {
            return EquipmentStatus.DEGRADED;
        }
        return EquipmentStatus.OPERATIONAL;
    }

    @Override
    protected Collection<AlarmSpec> typeAlarms() {
        boolean both = channelAFailed() && channelBFailed();
        Severity channelSeverity = both ? Severity.MAJOR : Severity.MINOR;
        List<AlarmSpec> specs = new ArrayList<>();
        if (channelAFailed()) {
            specs.add(new AlarmSpec(201, 0, "", channelSeverity, "Channel A failed"));
        }
        if (channelBFailed()) {
            specs.add(new AlarmSpec(202, 0, "", channelSeverity, "Channel B failed"));
        }
        if (hasFault(Fault.DEGRADE)) {
            specs.add(new AlarmSpec(204, 0, "", Severity.MINOR, "Low plot rate"));
        }
        return specs;
    }

    @Override
    protected void updateValues(Instant now, RandomGenerator rnd) {
        rotationDraw = rnd.nextDouble(-1.0, 1.0);
        plotsDraw = rnd.nextDouble(-1.0, 1.0);
    }

    private long rotationPeriodMs() {
        if (status() == EquipmentStatus.FAILED) {
            return 0;
        }
        return NOMINAL_ROTATION_MS + Math.round(rotationDraw * ROTATION_JITTER_MS);
    }

    private long plotsPerScan() {
        if (status() == EquipmentStatus.FAILED) {
            return 0;
        }
        return hasFault(Fault.DEGRADE)
                ? DEGRADED_PLOTS + Math.round(plotsDraw * DEGRADED_PLOTS_JITTER)
                : NOMINAL_PLOTS + Math.round(plotsDraw * NOMINAL_PLOTS_JITTER);
    }

    private static EquipmentStatus channelStatus(boolean failed) {
        return failed ? EquipmentStatus.FAILED : EquipmentStatus.OPERATIONAL;
    }

    @Override
    protected void putTypeObjects(MibView.Builder b) {
        b.put(AtmTsOids.scalar(AtmTsOids.RADAR_ROTATION_PERIOD_MS), new UnsignedInteger32(rotationPeriodMs()));
        b.put(AtmTsOids.scalar(AtmTsOids.RADAR_PLOTS_PER_SCAN), new Gauge32(plotsPerScan()));
        b.put(AtmTsOids.scalar(AtmTsOids.RADAR_ACTIVE_CHANNEL), new Integer32(activeChannel().code()));
        b.put(AtmTsOids.scalar(AtmTsOids.RADAR_CHANNEL_A_STATUS),
                new Integer32(channelStatus(channelAFailed()).code()));
        b.put(AtmTsOids.scalar(AtmTsOids.RADAR_CHANNEL_B_STATUS),
                new Integer32(channelStatus(channelBFailed()).code()));
    }

    @Override
    public Map<String, Object> values() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("rotationPeriodMs", rotationPeriodMs());
        values.put("plotsPerScan", plotsPerScan());
        values.put("activeChannel", activeChannel().name());
        values.put("channelAStatus", channelStatus(channelAFailed()).name());
        values.put("channelBStatus", channelStatus(channelBFailed()).name());
        return values;
    }
}
