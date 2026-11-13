package io.github.selvitopu.atmsup.simulator.model;

import static io.github.selvitopu.atmsup.mib.AtmTsOids.ALARM_SEVERITY;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_STATUS;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.RADAR_ACTIVE_CHANNEL;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.RADAR_CHANNEL_A_STATUS;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.RADAR_CHANNEL_B_STATUS;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.RADAR_PLOTS_PER_SCAN;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.RADAR_ROTATION_PERIOD_MS;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.scalar;
import static io.github.selvitopu.atmsup.mib.EquipmentStatus.DEGRADED;
import static io.github.selvitopu.atmsup.mib.EquipmentStatus.FAILED;
import static io.github.selvitopu.atmsup.mib.EquipmentStatus.OPERATIONAL;
import static io.github.selvitopu.atmsup.mib.Role.STANDALONE;
import static io.github.selvitopu.atmsup.mib.Severity.CRITICAL;
import static io.github.selvitopu.atmsup.mib.Severity.MAJOR;
import static io.github.selvitopu.atmsup.mib.Severity.MINOR;
import static io.github.selvitopu.atmsup.simulator.model.Fault.CHANNEL_A_FAIL;
import static io.github.selvitopu.atmsup.simulator.model.Fault.CHANNEL_B_FAIL;
import static io.github.selvitopu.atmsup.simulator.model.Fault.DEGRADE;
import static io.github.selvitopu.atmsup.simulator.model.Fault.FAIL;
import static io.github.selvitopu.atmsup.simulator.model.Fault.RECOVER;
import static io.github.selvitopu.atmsup.simulator.model.Fault.SENSOR_LOST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.DeviceType;
import io.github.selvitopu.atmsup.inventory.Group;
import io.github.selvitopu.atmsup.mib.AtmTsOids;
import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.AlarmCleared;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.AlarmRaised;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.StatusChange;
import java.time.Duration;
import java.time.Instant;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.Gauge32;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.UnsignedInteger32;
import org.snmp4j.smi.VariableBinding;

class RadarModelTest {

    private final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    private final Instant t1 = t0.plusSeconds(5);
    private final SplittableRandom rnd = new SplittableRandom(42);
    private final RadarModel radar = new RadarModel(
            new Device("RAD-N", DeviceType.RADAR, "Radar North", Group.SURVEILLANCE, "localhost", 16101, null,
                    Duration.ofSeconds(5)), t0);

    private static ActiveAlarm active(int code, Severity severity, Instant raisedAt) {
        return new ActiveAlarm(code, 0, "", severity, text(code), raisedAt);
    }

    private static String text(int code) {
        return switch (code) {
            case 101 -> "Equipment failed";
            case 102 -> "Equipment degraded";
            case 201 -> "Channel A failed";
            case 202 -> "Channel B failed";
            case 204 -> "Low plot rate";
            default -> throw new IllegalArgumentException();
        };
    }

    @Test
    void nominalIsOperationalWithoutAlarms() {
        assertThat(radar.status()).isEqualTo(OPERATIONAL);
        assertThat(radar.alarms()).isEmpty();
    }

    @Test
    void channelAFailSwitchesToBAndDegrades() {
        assertThat(radar.applyFault(new FaultRequest(CHANNEL_A_FAIL, null), t0)).containsExactly(
                new StatusChange(DEGRADED, STANDALONE),
                new AlarmRaised(active(102, MINOR, t0)),
                new AlarmRaised(active(201, MINOR, t0)));
        assertThat(radar.view(t0).get(scalar(RADAR_ACTIVE_CHANNEL))).map(VariableBinding::getVariable)
                .contains(new Integer32(2));
    }

    @Test
    void bothChannelsFailedIsFailedWithEscalation() {
        radar.applyFault(new FaultRequest(CHANNEL_A_FAIL, null), t0);
        assertThat(radar.applyFault(new FaultRequest(CHANNEL_B_FAIL, null), t1)).containsExactly(
                new StatusChange(FAILED, STANDALONE),
                new AlarmCleared(102, 0),
                new AlarmRaised(active(101, CRITICAL, t1)),
                new AlarmRaised(active(201, MAJOR, t0)),
                new AlarmRaised(active(202, MAJOR, t1)));
        assertThat(radar.status()).isEqualTo(FAILED);
    }

    @Test
    void recoverClearsEverything() {
        radar.applyFault(new FaultRequest(CHANNEL_A_FAIL, null), t0);
        radar.applyFault(new FaultRequest(CHANNEL_B_FAIL, null), t0);
        radar.applyFault(new FaultRequest(Fault.UNREACHABLE, null), t0);
        assertThat(radar.applyFault(new FaultRequest(RECOVER, null), t1)).containsExactly(
                new StatusChange(OPERATIONAL, STANDALONE),
                new AlarmCleared(101, 0), new AlarmCleared(201, 0), new AlarmCleared(202, 0));
        assertThat(radar.faults()).isEmpty();
        assertThat(radar.unreachable()).isFalse();
    }

    @Test
    void failFaultFailsBothChannelsAndZeroesValues() {
        radar.applyFault(new FaultRequest(FAIL, null), t0);
        radar.tick(t1, rnd);
        assertThat(radar.status()).isEqualTo(FAILED);
        assertThat(radar.alarms()).extracting(ActiveAlarm::code).containsExactly(101, 201, 202);
        assertThat(radar.values()).containsEntry("rotationPeriodMs", 0L).containsEntry("plotsPerScan", 0L)
                .containsEntry("channelAStatus", "FAILED").containsEntry("channelBStatus", "FAILED");
    }

    @Test
    void degradeLowersPlotRate() {
        radar.applyFault(new FaultRequest(DEGRADE, null), t0);
        radar.tick(t1, rnd);
        assertThat(radar.status()).isEqualTo(DEGRADED);
        assertThat(radar.alarms()).extracting(ActiveAlarm::code).containsExactly(102, 204);
        Gauge32 plots = (Gauge32) radar.view(t1).get(scalar(RADAR_PLOTS_PER_SCAN)).orElseThrow().getVariable();
        assertThat(plots.getValue()).isBetween(110L, 130L);
    }

    @Test
    void nominalValuesStayInRange() {
        for (int i = 0; i < 200; i++) {
            radar.tick(t0.plusSeconds(i), rnd);
            long period = (Long) radar.values().get("rotationPeriodMs");
            long plots = (Long) radar.values().get("plotsPerScan");
            assertThat(period).isBetween(4250L, 4550L);
            assertThat(plots).isBetween(280L, 320L);
        }
    }

    @Test
    void sensorLostIsNotApplicable() {
        assertThatThrownBy(() -> radar.applyFault(new FaultRequest(SENSOR_LOST, "x"), t0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Fault SENSOR_LOST not applicable to RADAR");
    }

    @Test
    void unreachableAndDropTrapsAreFlagsWithoutStatusEffect() {
        assertThat(radar.applyFault(new FaultRequest(Fault.UNREACHABLE, null), t0)).isEmpty();
        assertThat(radar.applyFault(new FaultRequest(Fault.DROP_TRAPS, null), t0)).isEmpty();
        assertThat(radar.unreachable()).isTrue();
        assertThat(radar.dropTraps()).isTrue();
        assertThat(radar.status()).isEqualTo(OPERATIONAL);
    }

    @Test
    void maintenanceRaisesWarning103() {
        radar.setMaintenance(true, t0);
        assertThat(radar.alarms()).extracting(ActiveAlarm::code).containsExactly(103);
        assertThat(radar.alarms().get(0).severity()).isEqualTo(Severity.WARNING);
        radar.setMaintenance(false, t1);
        assertThat(radar.alarms()).isEmpty();
    }

    @Test
    void switchoverAlarmLasts60Seconds() {
        radar.setRole(Role.ACTIVE, t0, true);
        assertThat(radar.alarms()).extracting(ActiveAlarm::code).containsExactly(104);
        radar.tick(t0.plusSeconds(59), rnd);
        assertThat(radar.alarms()).extracting(ActiveAlarm::code).containsExactly(104);
        radar.tick(t0.plusSeconds(60), rnd);
        assertThat(radar.alarms()).isEmpty();
    }

    @Test
    void demotionToStandbyClearsSwitchoverAlarm() {
        radar.setRole(Role.ACTIVE, t0, true);
        radar.setRole(Role.STANDBY, t0.plusSeconds(10));
        assertThat(radar.alarms()).isEmpty();
        radar.setRole(Role.ACTIVE, t0.plusSeconds(20));
        assertThat(radar.alarms()).isEmpty();
    }

    @Test
    void activeWithoutSwitchoverLeavesSwitchoverAlarmUntouched() {
        radar.setRole(Role.ACTIVE, t0, true);
        radar.setRole(Role.ACTIVE, t0.plusSeconds(10), false);
        assertThat(radar.alarms()).extracting(ActiveAlarm::code).containsExactly(104);
        radar.tick(t0.plusSeconds(60), rnd);
        assertThat(radar.alarms()).isEmpty();
    }

    @Test
    void initialStatusFollowsComputedStatus() {
        assertThat(radar.status()).isEqualTo(OPERATIONAL);
    }

    @Test
    void viewContainsSystemEquipmentAlarmAndRadarObjects() {
        radar.applyFault(new FaultRequest(CHANNEL_A_FAIL, null), t0);
        var view = radar.view(t0.plusSeconds(10));

        OID sev = new OID(ALARM_SEVERITY).append(201).append(0);
        assertThat(view.get(sev)).map(VariableBinding::getVariable).contains(new Integer32(5));
        assertThat(view.get(scalar(EQ_STATUS))).map(VariableBinding::getVariable).contains(new Integer32(2));
        assertThat(view.get(SnmpConstants.sysName)).map(VariableBinding::getVariable)
                .contains(new OctetString("RAD-N"));
        assertThat(view.get(SnmpConstants.sysDescr)).map(VariableBinding::getVariable)
                .contains(new OctetString("Demo ACC simulated RADAR RAD-N"));
        assertThat(view.get(SnmpConstants.sysObjectID)).map(VariableBinding::getVariable)
                .contains(AtmTsOids.ROOT);
        assertThat(view.get(SnmpConstants.sysUpTime)).map(VariableBinding::getVariable)
                .contains(new TimeTicks(1000));
        assertThat(view.get(scalar(AtmTsOids.EQ_SW_VERSION))).map(VariableBinding::getVariable)
                .contains(new OctetString("demo-1.0.0"));
        assertThat(view.get(scalar(AtmTsOids.EQ_COMMAND))).map(VariableBinding::getVariable)
                .contains(new Integer32(1));
        assertThat(view.get(scalar(AtmTsOids.EQ_MAINTENANCE_MODE))).map(VariableBinding::getVariable)
                .contains(new Integer32(2));
        assertThat(view.get(scalar(RADAR_ROTATION_PERIOD_MS)).orElseThrow().getVariable())
                .isInstanceOf(UnsignedInteger32.class);
        assertThat(view.get(scalar(RADAR_PLOTS_PER_SCAN)).orElseThrow().getVariable()).isInstanceOf(Gauge32.class);
        assertThat(view.get(scalar(RADAR_CHANNEL_A_STATUS))).map(VariableBinding::getVariable)
                .contains(new Integer32(3));
        assertThat(view.get(scalar(RADAR_CHANNEL_B_STATUS))).map(VariableBinding::getVariable)
                .contains(new Integer32(1));
    }
}
