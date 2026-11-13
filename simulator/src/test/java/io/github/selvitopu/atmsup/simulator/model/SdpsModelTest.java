package io.github.selvitopu.atmsup.simulator.model;

import static io.github.selvitopu.atmsup.mib.AtmTsOids.SDPS_LATENCY_MS;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.SDPS_SENSOR_INPUT_STATUS;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.SDPS_SENSOR_NAME;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.SDPS_TRACK_COUNT;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.scalar;
import static io.github.selvitopu.atmsup.mib.EquipmentStatus.DEGRADED;
import static io.github.selvitopu.atmsup.mib.EquipmentStatus.FAILED;
import static io.github.selvitopu.atmsup.mib.EquipmentStatus.OPERATIONAL;
import static io.github.selvitopu.atmsup.mib.Role.ACTIVE;
import static io.github.selvitopu.atmsup.mib.Severity.MAJOR;
import static io.github.selvitopu.atmsup.mib.Severity.MINOR;
import static io.github.selvitopu.atmsup.simulator.model.Fault.DEGRADE;
import static io.github.selvitopu.atmsup.simulator.model.Fault.FAIL;
import static io.github.selvitopu.atmsup.simulator.model.Fault.RECOVER;
import static io.github.selvitopu.atmsup.simulator.model.Fault.SENSOR_LOST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.DeviceType;
import io.github.selvitopu.atmsup.inventory.Group;
import io.github.selvitopu.atmsup.mib.SensorInputStatus;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.AlarmCleared;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.AlarmRaised;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.StatusChange;
import io.github.selvitopu.atmsup.simulator.model.SdpsModel.SensorRow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.snmp4j.smi.Gauge32;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.VariableBinding;

class SdpsModelTest {

    private final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    private final SplittableRandom rnd = new SplittableRandom(7);
    private final SdpsModel sdps = new SdpsModel(
            new Device("SDPS-A", DeviceType.SDPS, "SDPS A", Group.SURVEILLANCE, "localhost", 16104, "SDPS-B",
                    Duration.ofSeconds(5)), ACTIVE, t0, List.of("RAD-N", "RAD-E", "RAD-S"));

    private long value(String key) {
        return (Long) sdps.values().get(key);
    }

    @Test
    void lostFeedRaisesOneAlarmPerSensor() {
        assertThat(sdps.updateFeeds(Map.of("RAD-N", false, "RAD-E", false, "RAD-S", true), t0)).containsExactly(
                new AlarmRaised(new ActiveAlarm(401, 1, "RAD-N", MINOR, "Sensor input lost", t0)),
                new AlarmRaised(new ActiveAlarm(401, 2, "RAD-E", MINOR, "Sensor input lost", t0)));
        assertThat(sdps.status()).isEqualTo(OPERATIONAL);
        assertThat(sdps.view(t0).get(new OID(SDPS_SENSOR_INPUT_STATUS).append(2)))
                .map(VariableBinding::getVariable).contains(new Integer32(2));
        assertThat(sdps.view(t0).get(new OID(SDPS_SENSOR_NAME).append(2)))
                .map(VariableBinding::getVariable).contains(new OctetString("RAD-E"));
        assertThat(sdps.sensors()).containsExactly(
                new SensorRow(1, "RAD-N", SensorInputStatus.LOST),
                new SensorRow(2, "RAD-E", SensorInputStatus.LOST),
                new SensorRow(3, "RAD-S", SensorInputStatus.RECEIVING));
    }

    @Test
    void restoredFeedClearsAlarm() {
        sdps.updateFeeds(Map.of("RAD-N", false), t0);
        assertThat(sdps.updateFeeds(Map.of("RAD-N", true), t0.plusSeconds(5)))
                .containsExactly(new AlarmCleared(401, 1));
        assertThat(sdps.alarms()).isEmpty();
    }

    @Test
    void missingFeedEntryCountsAsReceiving() {
        assertThat(sdps.updateFeeds(Map.of(), t0)).isEmpty();
        assertThat(sdps.sensors()).allMatch(r -> r.status() == SensorInputStatus.RECEIVING);
    }

    @Test
    void sensorLostFaultMarksNamedSensorLost() {
        assertThat(sdps.applyFault(new FaultRequest(SENSOR_LOST, "RAD-S"), t0)).containsExactly(
                new AlarmRaised(new ActiveAlarm(401, 3, "RAD-S", MINOR, "Sensor input lost", t0)));
        assertThat(sdps.status()).isEqualTo(OPERATIONAL);
        assertThat(sdps.applyFault(new FaultRequest(RECOVER, null), t0.plusSeconds(1)))
                .containsExactly(new AlarmCleared(401, 3));
    }

    @Test
    void sensorLostFaultNeedsKnownTarget() {
        assertThatThrownBy(() -> sdps.applyFault(new FaultRequest(SENSOR_LOST, "RAD-X"), t0))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Unknown sensor RAD-X");
        assertThat(sdps.faults()).isEmpty();
    }

    @Test
    void degradeRaisesLatencyAlarm() {
        assertThat(sdps.applyFault(new FaultRequest(DEGRADE, null), t0)).containsExactly(
                new StatusChange(DEGRADED, ACTIVE),
                new AlarmRaised(new ActiveAlarm(102, 0, "", MINOR, "Equipment degraded", t0)),
                new AlarmRaised(new ActiveAlarm(402, 0, "", MAJOR, "Processing latency high", t0)));
        sdps.tick(t0.plusSeconds(5), rnd);
        assertThat(value("latencyMs")).isBetween(620L, 680L);
        assertThat(sdps.view(t0).get(scalar(SDPS_LATENCY_MS))).map(VariableBinding::getVariable)
                .hasValueSatisfying(v -> assertThat(v).isInstanceOf(Gauge32.class));
    }

    @Test
    void failZeroesValues() {
        sdps.tick(t0, rnd);
        assertThat(value("trackCount")).isPositive();
        sdps.applyFault(new FaultRequest(FAIL, null), t0.plusSeconds(1));
        assertThat(sdps.status()).isEqualTo(FAILED);
        assertThat(value("trackCount")).isZero();
        assertThat(value("latencyMs")).isZero();
        assertThat(sdps.view(t0).get(scalar(SDPS_TRACK_COUNT))).map(VariableBinding::getVariable)
                .contains(new Gauge32(0));
    }

    @Test
    void nominalValuesStayInRange() {
        for (int i = 0; i < 200; i++) {
            sdps.tick(t0.plusSeconds(5L * i), rnd);
            assertThat(value("trackCount")).isBetween(85L, 155L);
            assertThat(value("latencyMs")).isBetween(60L, 100L);
        }
    }

    @Test
    void trackCountScalesWithReceivingSensors() {
        sdps.tick(t0, rnd);
        long all = value("trackCount");
        sdps.updateFeeds(Map.of("RAD-N", false, "RAD-E", false, "RAD-S", false), t0);
        assertThat(value("trackCount")).isLessThanOrEqualTo(5L).isLessThan(all);
    }
}
