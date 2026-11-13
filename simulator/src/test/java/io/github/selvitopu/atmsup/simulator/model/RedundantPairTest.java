package io.github.selvitopu.atmsup.simulator.model;

import static io.github.selvitopu.atmsup.mib.Role.ACTIVE;
import static io.github.selvitopu.atmsup.mib.Role.STANDBY;
import static io.github.selvitopu.atmsup.mib.Severity.WARNING;
import static io.github.selvitopu.atmsup.simulator.model.Fault.DEGRADE;
import static io.github.selvitopu.atmsup.simulator.model.Fault.FAIL;
import static io.github.selvitopu.atmsup.simulator.model.Fault.RECOVER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.DeviceType;
import io.github.selvitopu.atmsup.inventory.Group;
import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.simulator.agent.SetCheck;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.AlarmCleared;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.AlarmRaised;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.Switchover;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.snmp4j.PDU;

class RedundantPairTest {

    private final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    private final FdpsModel a = fdps("FDPS-A", 16107, ACTIVE);
    private final FdpsModel b = fdps("FDPS-B", 16108, STANDBY);
    private final RedundantPair pair = new RedundantPair(a, b);

    private FdpsModel fdps(String id, int port, Role role) {
        return new FdpsModel(new Device(id, DeviceType.FDPS, id, Group.FLIGHT_DATA, "localhost", port,
                id.equals("FDPS-A") ? "FDPS-B" : "FDPS-A", Duration.ofSeconds(5)), role, t0);
    }

    private static ActiveAlarm active(int code, Instant at) {
        return new ActiveAlarm(code, 0, "", WARNING, "Switchover occurred", at);
    }

    @Test
    void firstStartsActiveSecondStandby() {
        assertThat(pair.active()).isSameAs(a);
        assertThat(pair.standby()).isSameAs(b);
        assertThat(pair.partnerOf("FDPS-A")).containsSame(b);
        assertThat(pair.partnerOf("FDPS-B")).containsSame(a);
        assertThat(pair.partnerOf("X")).isEmpty();
    }

    @Test
    void constructorRejectsWrongRoles() {
        assertThatThrownBy(() -> new RedundantPair(b, a)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void autoSwitchoverAfterThreeSeconds() {
        a.applyFault(new FaultRequest(FAIL, null), t0);
        assertThat(pair.tick(t0.plusMillis(2900))).isEmpty();
        assertThat(pair.tick(t0.plusSeconds(3))).containsExactly(
                new Emitted("FDPS-A", new Switchover(STANDBY)),
                new Emitted("FDPS-B", new Switchover(ACTIVE)),
                new Emitted("FDPS-B", new AlarmRaised(active(104, t0.plusSeconds(3)))));
        assertThat(pair.active().id()).isEqualTo("FDPS-B");
        assertThat(a.role()).isEqualTo(STANDBY);
        assertThat(pair.tick(t0.plusSeconds(4))).isEmpty();
    }

    @Test
    void autoSwitchoverToDegradedStandby() {
        b.applyFault(new FaultRequest(DEGRADE, null), t0);
        a.applyFault(new FaultRequest(FAIL, null), t0);
        assertThat(pair.tick(t0.plusSeconds(3))).extracting(Emitted::deviceId)
                .containsExactly("FDPS-A", "FDPS-B", "FDPS-B");
        assertThat(pair.active()).isSameAs(b);
    }

    @Test
    void noSwitchoverToFailedStandby() {
        a.applyFault(new FaultRequest(FAIL, null), t0);
        b.applyFault(new FaultRequest(FAIL, null), t0);
        assertThat(pair.tick(t0.plusSeconds(10))).isEmpty();
        assertThat(pair.active()).isSameAs(a);
    }

    @Test
    void noSwitchoverToStandbyInMaintenance() {
        b.setMaintenance(true, t0);
        a.applyFault(new FaultRequest(FAIL, null), t0);
        assertThat(pair.tick(t0.plusSeconds(10))).isEmpty();
        assertThat(pair.active()).isSameAs(a);
    }

    @Test
    void recoveryBeforeThreeSecondsAvoidsSwitchover() {
        a.applyFault(new FaultRequest(FAIL, null), t0);
        a.applyFault(new FaultRequest(RECOVER, null), t0.plusSeconds(2));
        assertThat(pair.tick(t0.plusSeconds(5))).isEmpty();
        assertThat(pair.active()).isSameAs(a);
    }

    @Test
    void becomeActiveIsIdempotentOnActive() {
        assertThat(pair.checkBecomeActive("FDPS-A")).isEqualTo(SetCheck.OK);
        assertThat(pair.becomeActive("FDPS-A", t0)).isEmpty();
        assertThat(pair.active()).isSameAs(a);
    }

    @Test
    void becomeActiveRejectsNonOperationalStandby() {
        b.applyFault(new FaultRequest(DEGRADE, null), t0);
        SetCheck check = pair.checkBecomeActive("FDPS-B");
        assertThat(check.reason()).isEqualTo("standby not operational");
        assertThat(check.errorStatus()).isEqualTo(PDU.inconsistentValue);
    }

    @Test
    void becomeActiveRejectsStandbyInMaintenance() {
        b.setMaintenance(true, t0);
        SetCheck check = pair.checkBecomeActive("FDPS-B");
        assertThat(check.reason()).isEqualTo("standby in maintenance");
        assertThat(check.errorStatus()).isEqualTo(PDU.inconsistentValue);
    }

    @Test
    void becomeActiveSwapsImmediately() {
        assertThat(pair.checkBecomeActive("FDPS-B")).isEqualTo(SetCheck.OK);
        assertThat(pair.becomeActive("FDPS-B", t0)).extracting(Emitted::deviceId)
                .containsExactly("FDPS-A", "FDPS-B", "FDPS-B");
        assertThat(pair.active()).isSameAs(b);
        assertThat(b.alarms()).extracting(ActiveAlarm::code).containsExactly(104);
    }

    @Test
    void secondSwapMovesTheSwitchoverAlarm() {
        pair.becomeActive("FDPS-B", t0);
        assertThat(pair.becomeActive("FDPS-A", t0.plusSeconds(10))).containsExactly(
                new Emitted("FDPS-B", new Switchover(STANDBY)),
                new Emitted("FDPS-A", new Switchover(ACTIVE)),
                new Emitted("FDPS-B", new AlarmCleared(104, 0)),
                new Emitted("FDPS-A", new AlarmRaised(active(104, t0.plusSeconds(10)))));
        assertThat(b.alarms()).isEmpty();
    }

    @Test
    void unknownDeviceIsRejected() {
        assertThat(pair.checkBecomeActive("X").errorStatus()).isEqualTo(PDU.inconsistentValue);
        assertThatThrownBy(() -> pair.becomeActive("X", t0)).isInstanceOf(IllegalArgumentException.class);
    }
}
