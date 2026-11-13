package io.github.selvitopu.atmsup.simulator.centre;

import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_COMMAND;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_MAINTENANCE_MODE;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.scalar;
import static io.github.selvitopu.atmsup.mib.EquipmentStatus.OPERATIONAL;
import static io.github.selvitopu.atmsup.mib.Role.ACTIVE;
import static io.github.selvitopu.atmsup.mib.Role.STANDBY;
import static io.github.selvitopu.atmsup.mib.Severity.WARNING;
import static io.github.selvitopu.atmsup.simulator.model.Fault.FAIL;
import static io.github.selvitopu.atmsup.simulator.model.Fault.UNREACHABLE;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.simulator.agent.SetCheck;
import io.github.selvitopu.atmsup.simulator.api.DeviceStateDto;
import io.github.selvitopu.atmsup.simulator.centre.CentreEvent.Notify;
import io.github.selvitopu.atmsup.simulator.centre.CentreEvent.Responding;
import io.github.selvitopu.atmsup.simulator.centre.CentreEvent.RestartCompleted;
import io.github.selvitopu.atmsup.simulator.centre.CentreEvent.RestartStarted;
import io.github.selvitopu.atmsup.simulator.fixture.TestInventories;
import io.github.selvitopu.atmsup.simulator.model.ActiveAlarm;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.AlarmRaised;
import io.github.selvitopu.atmsup.simulator.model.FaultRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;

class CommandEffectsTest {

    private final Instant t0 = Instant.parse("2026-11-13T10:00:00Z");
    private final AtomicReference<Instant> now = new AtomicReference<>(t0);
    private final InstantSource clock = now::get;
    private final List<List<CentreEvent>> batches = new CopyOnWriteArrayList<>();
    private Centre centre;

    @BeforeEach
    void setUp() {
        centre = new Centre(Inventory.from(TestInventories.v01(20000, 10162)), clock, new SplittableRandom(7),
                ofSeconds(8));
        centre.addListener(batches::add);
    }

    private void advance(Duration d) {
        now.updateAndGet(i -> i.plus(d));
    }

    private List<CentreEvent> events() {
        return batches.stream().flatMap(List::stream).toList();
    }

    private void set(String id, OID oid, int value) {
        SetCheck check = centre.setHandler(id).check(scalar(oid), new Integer32(value));
        assertThat(check).isEqualTo(SetCheck.OK);
        centre.setHandler(id).apply(scalar(oid), new Integer32(value));
    }

    @Test
    void restartRequiresMaintenance() {
        SetCheck c = centre.setHandler("FDPS-A").check(scalar(EQ_COMMAND), new Integer32(3));
        assertThat(c.reason()).isEqualTo("restart requires maintenance mode");
        assertThat(centre.device("FDPS-A").values().get("lastCommandResult")).isEqualTo("REJECTED");
    }

    @Test
    void restartCycle() {
        centre.applyFault("FDPS-A", new FaultRequest(FAIL, null));
        advance(ofSeconds(3));
        centre.tick(); // automatic switchover: FDPS-B becomes active
        set("FDPS-A", EQ_MAINTENANCE_MODE, 1);
        set("FDPS-A", EQ_COMMAND, 3);
        assertThat(events()).contains(new RestartStarted("FDPS-A"));
        assertThat(centre.device("FDPS-A").values().get("lastCommandResult")).isEqualTo("IN_PROGRESS");

        advance(ofMillis(7900));
        centre.tick();
        assertThat(events()).doesNotContain(new RestartCompleted("FDPS-A"));
        advance(ofMillis(100));
        centre.tick();
        assertThat(events()).contains(new RestartCompleted("FDPS-A"));

        DeviceStateDto a = centre.device("FDPS-A");
        assertThat(a.status()).isEqualTo(OPERATIONAL);
        assertThat(a.role()).isEqualTo(STANDBY);
        assertThat(a.faults()).isEmpty();
        assertThat(a.maintenance()).isTrue();
        assertThat(a.values().get("lastCommandResult")).isEqualTo("SUCCESS");
    }

    @Test
    void completionComesFirstInItsBatchAndRestartClearsFlags() {
        centre.applyFault("FDPS-A", new FaultRequest(UNREACHABLE, null));
        centre.applyFault("FDPS-A", new FaultRequest(FAIL, null));
        set("FDPS-A", EQ_MAINTENANCE_MODE, 1);
        set("FDPS-A", EQ_COMMAND, 3);
        advance(ofSeconds(8));
        centre.tick();

        List<CentreEvent> last = batches.get(batches.size() - 1);
        assertThat(last.get(0)).isEqualTo(new RestartCompleted("FDPS-A"));
        assertThat(last).contains(new Responding("FDPS-A", true));
        int firstNotify = last.indexOf(last.stream().filter(e -> e instanceof Notify).findFirst().orElseThrow());
        assertThat(last.indexOf(new Responding("FDPS-A", true))).isLessThan(firstNotify);
        assertThat(centre.device("FDPS-A").role()).isEqualTo(ACTIVE);
    }

    @Test
    void secondRestartWhileRestartingIsInconsistent() {
        set("FDPS-A", EQ_MAINTENANCE_MODE, 1);
        set("FDPS-A", EQ_COMMAND, 3);
        assertThat(centre.setHandler("FDPS-A").check(scalar(EQ_COMMAND), new Integer32(3)).reason())
                .isEqualTo("restart already in progress");
    }

    @Test
    void becomeActiveOnStandaloneIsInconsistent() {
        assertThat(centre.setHandler("RAD-N").check(scalar(EQ_COMMAND), new Integer32(2)).reason())
                .isEqualTo("not redundant");
    }

    @Test
    void becomeActiveSwapsThePair() {
        set("SDPS-B", EQ_COMMAND, 2);
        assertThat(centre.device("SDPS-B").role()).isEqualTo(ACTIVE);
        assertThat(centre.device("SDPS-A").role()).isEqualTo(STANDBY);
        assertThat(centre.device("SDPS-B").values().get("lastCommandResult")).isEqualTo("SUCCESS");
    }

    @Test
    void failedBecomeActiveReadsBackRejected() {
        set("SDPS-B", EQ_MAINTENANCE_MODE, 1);
        assertThat(centre.setHandler("SDPS-B").check(scalar(EQ_COMMAND), new Integer32(2)).reason())
                .isEqualTo("standby in maintenance");
        assertThat(centre.device("SDPS-B").values().get("lastCommandResult")).isEqualTo("REJECTED");
    }

    @Test
    void resetCountersIsAnAcceptedNoOp() {
        set("RAD-N", EQ_COMMAND, 4);
        assertThat(centre.device("RAD-N").values().get("lastCommandResult")).isEqualTo("SUCCESS");
    }

    @Test
    void maintenanceRaises103AndOffClearsIt() {
        set("RAD-N", EQ_MAINTENANCE_MODE, 1);
        assertThat(events()).contains(new Notify("RAD-N",
                new AlarmRaised(new ActiveAlarm(103, 0, "", WARNING, "Maintenance mode on", t0))));
        set("RAD-N", EQ_MAINTENANCE_MODE, 2);
        assertThat(centre.device("RAD-N").maintenance()).isFalse();
        assertThat(centre.device("RAD-N").alarms()).isEmpty();
    }
}
