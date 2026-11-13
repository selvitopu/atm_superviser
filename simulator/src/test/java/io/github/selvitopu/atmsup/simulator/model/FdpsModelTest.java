package io.github.selvitopu.atmsup.simulator.model;

import static io.github.selvitopu.atmsup.mib.AtmTsOids.FDPS_FLIGHT_PLAN_COUNT;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.scalar;
import static io.github.selvitopu.atmsup.mib.EquipmentStatus.DEGRADED;
import static io.github.selvitopu.atmsup.mib.EquipmentStatus.FAILED;
import static io.github.selvitopu.atmsup.mib.Role.ACTIVE;
import static io.github.selvitopu.atmsup.mib.Severity.MAJOR;
import static io.github.selvitopu.atmsup.mib.Severity.MINOR;
import static io.github.selvitopu.atmsup.simulator.model.Fault.CHANNEL_A_FAIL;
import static io.github.selvitopu.atmsup.simulator.model.Fault.DEGRADE;
import static io.github.selvitopu.atmsup.simulator.model.Fault.FAIL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.DeviceType;
import io.github.selvitopu.atmsup.inventory.Group;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.AlarmRaised;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.StatusChange;
import java.time.Duration;
import java.time.Instant;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.snmp4j.smi.Gauge32;
import org.snmp4j.smi.VariableBinding;

class FdpsModelTest {

    private final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    private final SplittableRandom rnd = new SplittableRandom(11);
    private final FdpsModel fdps = new FdpsModel(
            new Device("FDPS-A", DeviceType.FDPS, "FDPS A", Group.FLIGHT_DATA, "localhost", 16107, "FDPS-B",
                    Duration.ofSeconds(5)), ACTIVE, t0);

    private long value(String key) {
        return (Long) fdps.values().get(key);
    }

    @Test
    void degradeRaisesBacklog() {
        assertThat(fdps.applyFault(new FaultRequest(DEGRADE, null), t0)).containsExactly(
                new StatusChange(DEGRADED, ACTIVE),
                new AlarmRaised(new ActiveAlarm(102, 0, "", MINOR, "Equipment degraded", t0)),
                new AlarmRaised(new ActiveAlarm(501, 0, "", MAJOR, "Message queue backlog", t0)));
        fdps.tick(t0.plusSeconds(5), rnd);
        assertThat(value("queueDepth")).isBetween(250L, 270L);
    }

    @Test
    void failZeroesValues() {
        fdps.tick(t0, rnd);
        fdps.applyFault(new FaultRequest(FAIL, null), t0.plusSeconds(1));
        assertThat(fdps.status()).isEqualTo(FAILED);
        assertThat(fdps.values()).containsEntry("flightPlanCount", 0L).containsEntry("queueDepth", 0L)
                .containsEntry("latencyMs", 0L);
        assertThat(fdps.view(t0).get(scalar(FDPS_FLIGHT_PLAN_COUNT))).map(VariableBinding::getVariable)
                .contains(new Gauge32(0));
    }

    @Test
    void nominalValuesStayInRange() {
        for (int i = 0; i < 200; i++) {
            fdps.tick(t0.plusSeconds(5L * i), rnd);
            assertThat(value("flightPlanCount")).isBetween(410L, 490L);
            assertThat(value("queueDepth")).isBetween(2L, 8L);
            assertThat(value("latencyMs")).isBetween(110L, 190L);
        }
        assertThat(fdps.alarms()).isEmpty();
    }

    @Test
    void radarFaultsDoNotApply() {
        assertThatThrownBy(() -> fdps.applyFault(new FaultRequest(CHANNEL_A_FAIL, null), t0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
