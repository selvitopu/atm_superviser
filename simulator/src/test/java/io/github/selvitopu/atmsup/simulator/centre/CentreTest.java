package io.github.selvitopu.atmsup.simulator.centre;

import static io.github.selvitopu.atmsup.mib.Severity.MINOR;
import static io.github.selvitopu.atmsup.simulator.model.Fault.DEGRADE;
import static io.github.selvitopu.atmsup.simulator.model.Fault.DROP_TRAPS;
import static io.github.selvitopu.atmsup.simulator.model.Fault.FAIL;
import static io.github.selvitopu.atmsup.simulator.model.Fault.RECOVER;
import static io.github.selvitopu.atmsup.simulator.model.Fault.SENSOR_LOST;
import static io.github.selvitopu.atmsup.simulator.model.Fault.UNREACHABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.selvitopu.atmsup.inventory.DeviceProperties;
import io.github.selvitopu.atmsup.inventory.DeviceType;
import io.github.selvitopu.atmsup.inventory.Group;
import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.inventory.InventoryProperties;
import io.github.selvitopu.atmsup.inventory.TrapTarget;
import io.github.selvitopu.atmsup.mib.EquipmentStatus;
import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.simulator.api.DeviceStateDto;
import io.github.selvitopu.atmsup.simulator.centre.CentreEvent.DropTraps;
import io.github.selvitopu.atmsup.simulator.centre.CentreEvent.Notify;
import io.github.selvitopu.atmsup.simulator.centre.CentreEvent.Responding;
import io.github.selvitopu.atmsup.simulator.fixture.TestInventories;
import io.github.selvitopu.atmsup.simulator.model.ActiveAlarm;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.AlarmRaised;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.StatusChange;
import io.github.selvitopu.atmsup.simulator.model.FaultRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

class CentreTest {

    private final Instant t0 = Instant.parse("2026-11-13T10:00:00Z");
    private final TestClock clock = new TestClock(t0);
    private final RecordingListener listener = new RecordingListener();
    private Centre centre;

    @BeforeEach
    void setUp() {
        centre = new Centre(Inventory.from(TestInventories.v01(20000, 10162)), clock, new SplittableRandom(7));
        centre.addListener(listener);
    }

    @Test
    void radarFailurePropagatesToBothSdpsSensorTables() {
        centre.applyFault("RAD-N", new FaultRequest(FAIL, null));

        assertThat(listener.notifies("SDPS-A")).contains(
                new AlarmRaised(new ActiveAlarm(401, 1, "RAD-N", MINOR, "Sensor input lost", t0)));
        assertThat(listener.notifies("SDPS-B")).anyMatch(n -> n instanceof AlarmRaised a && a.alarm().code() == 401);
        assertThat(listener.notifies("RAD-N").get(0))
                .isEqualTo(new StatusChange(EquipmentStatus.FAILED, Role.STANDALONE));
    }

    @Test
    void unreachableRadarStillFeedsSdps() {
        centre.applyFault("RAD-N", new FaultRequest(UNREACHABLE, null));

        assertThat(listener.events()).containsExactly(new Responding("RAD-N", false));
        assertThat(centre.device("SDPS-A").alarms()).isEmpty();
        assertThat(centre.device("RAD-N").responding()).isFalse();
    }

    @Test
    void dropTrapsAndRecoverRevertTheFlags() {
        centre.applyFault("RAD-E", new FaultRequest(UNREACHABLE, null));
        centre.applyFault("RAD-E", new FaultRequest(DROP_TRAPS, null));
        centre.applyFault("RAD-E", new FaultRequest(RECOVER, null));

        assertThat(listener.events()).containsExactly(new Responding("RAD-E", false), new DropTraps("RAD-E", true),
                new Responding("RAD-E", true), new DropTraps("RAD-E", false));
        assertThat(centre.device("RAD-E").faults()).isEmpty();
    }

    @Test
    void eventsAreDeliveredInOneBatchInTheDocumentedOrder() {
        centre.applyFault("RAD-N", new FaultRequest(DROP_TRAPS, null));
        centre.applyFault("RAD-N", new FaultRequest(FAIL, null));
        listener.clear();

        centre.applyFault("RAD-N", new FaultRequest(RECOVER, null));

        List<CentreEvent> events = listener.events();
        assertThat(events.get(0)).isEqualTo(new DropTraps("RAD-N", false));
        assertThat(events.subList(1, events.size())).isNotEmpty().allMatch(e -> e instanceof Notify);
        assertThat(listener.batches()).hasSize(1);
    }

    @Test
    void deviceStateRendersFaultsAndValues() {
        centre.applyFault("SDPS-A", new FaultRequest(SENSOR_LOST, "RAD-E"));
        centre.applyFault("SDPS-A", new FaultRequest(DEGRADE, null));

        DeviceStateDto s = centre.device("SDPS-A");
        assertThat(s.faults()).containsExactly("DEGRADE", "SENSOR_LOST:RAD-E");
        assertThat(s.role()).isEqualTo(Role.ACTIVE);
        assertThat(s.values()).containsKeys("trackCount", "latencyMs", "lastCommandResult");
        assertThat(s.values().get("lastCommandResult")).isEqualTo("NONE");
        assertThat(centre.device("RAD-N").values()).containsKeys("rotationPeriodMs", "plotsPerScan",
                "activeChannel", "channelAStatus", "channelBStatus");
        assertThat(centre.devices()).hasSize(7);
    }

    @Test
    void viewsAreRebuiltAfterEveryChange() {
        var before = centre.view("RAD-N");
        centre.applyFault("RAD-N", new FaultRequest(FAIL, null));

        assertThat(centre.view("RAD-N")).isNotSameAs(before);
        assertThat(centre.view("RAD-N").size()).isGreaterThan(0);
    }

    @Test
    void resetRestoresNominal() {
        centre.applyFault("RAD-N", new FaultRequest(FAIL, null));
        centre.applyFault("FDPS-A", new FaultRequest(FAIL, null));
        clock.advance(Duration.ofSeconds(3));
        centre.tick();
        assertThat(centre.device("FDPS-B").role()).isEqualTo(Role.ACTIVE);

        centre.reset();

        assertThat(centre.device("FDPS-A").role()).isEqualTo(Role.ACTIVE);
        assertThat(centre.device("FDPS-B").role()).isEqualTo(Role.STANDBY);
        assertThat(centre.devices()).allSatisfy(d -> {
            assertThat(d.status()).isEqualTo(EquipmentStatus.OPERATIONAL);
            assertThat(d.alarms()).isEmpty();
            assertThat(d.faults()).isEmpty();
            assertThat(d.maintenance()).isFalse();
        });
    }

    @Test
    void tickAdvancesDevicesBeforePairsSoFailoverHappensAfterThreeSeconds() {
        centre.applyFault("SDPS-A", new FaultRequest(FAIL, null));
        clock.advance(Duration.ofSeconds(2));
        centre.tick();
        assertThat(centre.device("SDPS-A").role()).isEqualTo(Role.ACTIVE);

        clock.advance(Duration.ofSeconds(1));
        centre.tick();
        assertThat(centre.device("SDPS-A").role()).isEqualTo(Role.STANDBY);
        assertThat(centre.device("SDPS-B").role()).isEqualTo(Role.ACTIVE);
    }

    @Test
    void listenersRunOutsideTheLockSoTheyMayReadTheCentre() {
        List<Integer> seen = new CopyOnWriteArrayList<>();
        centre.addListener(events -> seen.add(centre.devices().size()));

        centre.applyFault("RAD-S", new FaultRequest(FAIL, null));

        assertThat(seen).containsExactly(7);
    }

    @Test
    @Timeout(20)
    void aSetRequestHoldsTheCentreLockFromFirstCheckToLastApply() throws Exception {
        CompletableFuture<Object> fault = new CompletableFuture<>();
        CompletableFuture<Object> started = new CompletableFuture<>();
        centre.setHandler("FDPS-B").atomically(() -> {
            CompletableFuture.runAsync(() -> {
                started.complete(null);
                centre.applyFault("FDPS-B", new FaultRequest(FAIL, null));
                fault.complete(null);
            });
            started.join();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            assertThat(fault).isNotDone();
            return null;
        });
        fault.get(2, TimeUnit.SECONDS);
    }

    @Test
    @Timeout(20)
    void concurrentMutationsWithReadingListenersDoNotDeadlock() throws Exception {
        List<List<CentreEvent>> delivered = new CopyOnWriteArrayList<>();
        centre.addListener(events -> {
            centre.devices(); // reading from a listener must not deadlock against a mutation waiting to deliver
            delivered.add(events);
        });
        String[] ids = {"RAD-N", "RAD-E", "RAD-S", "SDPS-A"};
        List<Thread> threads = new ArrayList<>();
        for (String id : ids) {
            Thread t = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    centre.applyFault(id, new FaultRequest(i % 2 == 0 ? FAIL : RECOVER, null));
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        assertThat(delivered).isNotEmpty().allSatisfy(batch -> assertThat(batch).isNotEmpty());
        assertThat(centre.devices()).hasSize(7);
    }

    @Test
    void aFailingListenerDoesNotStopTheOthersOrTheMutation() {
        Centre local = new Centre(Inventory.from(TestInventories.v01(20000, 10162)), clock, new SplittableRandom(7));
        local.addListener(events -> {
            throw new IllegalStateException("boom");
        });
        RecordingListener later = new RecordingListener();
        local.addListener(later);

        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Centre.class);
        ListAppender<ILoggingEvent> warnings = new ListAppender<>();
        warnings.start();
        logger.addAppender(warnings);
        logger.setAdditive(false); // keep the expected WARN and stack trace out of the test console
        try {
            local.applyFault("RAD-N", new FaultRequest(FAIL, null));
        } finally {
            logger.setAdditive(true);
            logger.detachAppender(warnings);
        }

        assertThat(later.events()).isNotEmpty();
        assertThat(local.device("RAD-N").status()).isEqualTo(EquipmentStatus.FAILED);
        assertThat(warnings.list).hasSize(1);
        assertThat(warnings.list.get(0).getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void inapplicableFaultIsRejectedWithoutChange() {
        assertThatThrownBy(() -> centre.applyFault("RAD-N", new FaultRequest(SENSOR_LOST, "RAD-E")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Fault SENSOR_LOST not applicable to RADAR");
        assertThat(listener.events()).isEmpty();
    }

    @Test
    void unknownDeviceIsReported() {
        assertThatThrownBy(() -> centre.device("X-1")).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> centre.applyFault("X-1", new FaultRequest(FAIL, null)))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> centre.view("X-1")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void unsupportedTypeFailsAtStartup() {
        InventoryProperties base = TestInventories.v01(20000, 10162);
        List<DeviceProperties> devices = new ArrayList<>(base.devices());
        devices.add(new DeviceProperties("ADSB-1", DeviceType.ADSB, "ADS-B 1", Group.SURVEILLANCE, null, 20010,
                null, null));
        InventoryProperties withAdsb = new InventoryProperties(base.centre(), base.defaultHost(),
                base.defaultPollInterval(), new TrapTarget("127.0.0.1", 10162), devices);

        assertThatThrownBy(() -> new Centre(Inventory.from(withAdsb), clock, new SplittableRandom(7)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Device type ADSB is not supported in this version");
    }

    private static final class TestClock implements InstantSource {
        private volatile Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class RecordingListener implements CentreListener {
        private final List<List<CentreEvent>> batches = new CopyOnWriteArrayList<>();

        @Override
        public void on(List<CentreEvent> events) {
            batches.add(events);
        }

        List<List<CentreEvent>> batches() {
            return batches;
        }

        List<CentreEvent> events() {
            return batches.stream().flatMap(List::stream).toList();
        }

        List<DeviceNotification> notifies(String deviceId) {
            return events().stream().filter(e -> e instanceof Notify n && n.deviceId().equals(deviceId))
                    .map(e -> ((Notify) e).n()).toList();
        }

        void clear() {
            batches.clear();
        }
    }
}
