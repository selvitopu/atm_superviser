package io.github.selvitopu.atmsup.simulator.agent;

import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_ID;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_STATUS;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.scalar;
import static io.github.selvitopu.atmsup.simulator.model.Fault.DROP_TRAPS;
import static io.github.selvitopu.atmsup.simulator.model.Fault.FAIL;
import static io.github.selvitopu.atmsup.simulator.model.Fault.RECOVER;
import static io.github.selvitopu.atmsup.simulator.model.Fault.UNREACHABLE;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.inventory.SnmpProperties;
import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.simulator.centre.CentreEvent.Notify;
import io.github.selvitopu.atmsup.simulator.centre.CentreEvent.RestartCompleted;
import io.github.selvitopu.atmsup.simulator.centre.CentreEvent.RestartStarted;
import io.github.selvitopu.atmsup.simulator.fixture.FreePorts;
import io.github.selvitopu.atmsup.simulator.fixture.SimulatorFixture;
import io.github.selvitopu.atmsup.simulator.fixture.TestInventories;
import io.github.selvitopu.atmsup.simulator.fixture.TestManager;
import io.github.selvitopu.atmsup.simulator.fixture.TestTrapReceiver;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.Switchover;
import io.github.selvitopu.atmsup.simulator.model.FaultRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.snmp4j.PDU;
import org.snmp4j.smi.Integer32;

class AgentHostIT {

    private static final SnmpProperties CREDS = TestInventories.demoCredentials();

    @TempDir
    Path dataDir;

    private int firstPort;
    private TestTrapReceiver receiver;
    private SimulatorFixture sim;

    @BeforeEach
    void start() {
        firstPort = FreePorts.udpBlock(7);
        int trapPort = FreePorts.udp(1)[0];
        Inventory inventory = Inventory.from(TestInventories.v01(firstPort, trapPort));
        receiver = new TestTrapReceiver(trapPort, CREDS, inventory.devices());
        sim = SimulatorFixture.start(inventory, CREDS, dataDir);
    }

    @AfterEach
    void stop() {
        sim.close();
        receiver.close();
    }

    private Integer32 status(int port) {
        try (TestManager m = new TestManager("127.0.0.1", port, "monitor", CREDS.monitor().authPassword(),
                CREDS.monitor().privPassword())) {
            PDU p = m.get(scalar(EQ_STATUS));
            return p == null ? null : (Integer32) p.getVariable(scalar(EQ_STATUS));
        }
    }

    @Test
    void notificationsReachTheTrapReceiver() {
        sim.centre().applyFault("RAD-N", new FaultRequest(FAIL, null));

        // at least the status change (trap) and alarm 101 (inform) from RAD-N
        assertThat(receiver.awaitPdus(2, ofSeconds(3))).hasSize(2);
    }

    @Test
    void unreachableStopsAnsweringAndRecoverResumes() {
        sim.centre().applyFault("RAD-E", new FaultRequest(UNREACHABLE, null));
        assertThat(status(firstPort + 1)).isNull();

        sim.centre().applyFault("RAD-E", new FaultRequest(RECOVER, null));
        assertThat(status(firstPort + 1)).isEqualTo(new Integer32(1));
    }

    @Test
    void dropTrapsSilencesTheSender() {
        sim.centre().applyFault("RAD-S", new FaultRequest(DROP_TRAPS, null));
        sim.centre().applyFault("RAD-S", new FaultRequest(FAIL, null));

        // the SDPS units still announce the lost sensor, but nothing comes from RAD-S itself
        List<PDU> pdus = receiver.awaitPdus(10, ofSeconds(1));
        assertThat(pdus).isNotEmpty();
        assertThat(pdus).noneMatch(p -> p.getVariable(scalar(EQ_ID)).toString().equals("RAD-S"));
    }

    @Test
    void restartClosesTheAgentThenStartsANewOneWithANewSender() throws Exception {
        DeviceAgent oldAgent = sim.host().agent("RAD-N");
        NotificationSender oldSender = sim.host().sender("RAD-N");

        sim.host().on(List.of(new RestartStarted("RAD-N")));
        await().atMost(ofSeconds(2)).untilAsserted(() -> assertThat(oldAgent.snmp()).isNull());
        assertThat(status(firstPort)).isNull();

        sim.host().on(List.of(new RestartCompleted("RAD-N")));
        assertThat(sim.host().agent("RAD-N")).isNotSameAs(oldAgent);
        assertThat(sim.host().sender("RAD-N")).isNotSameAs(oldSender);
        assertThat(status(firstPort)).isEqualTo(new Integer32(1));
        assertThat(Files.readString(dataDir.resolve("engine-boots.json"))).contains("\"RAD-N\":2");

        // later events use the new pair: a trap still arrives
        sim.centre().applyFault("RAD-N", new FaultRequest(FAIL, null));
        assertThat(receiver.awaitPdus(1, ofSeconds(3))).hasSize(1);
    }

    @Test
    void aRestartedAgentKeepsTheCurrentFaultFlags() {
        sim.centre().applyFault("RAD-N", new FaultRequest(UNREACHABLE, null));

        sim.host().on(List.of(new RestartStarted("RAD-N")));
        await().atMost(ofSeconds(2)).until(() -> sim.host().agent("RAD-N").snmp() == null);
        sim.host().on(List.of(new RestartCompleted("RAD-N")));

        assertThat(status(firstPort)).isNull();
    }

    @Test
    void aBadEventIsLoggedAndLaterEventsStillRoute() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AgentHost.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        boolean additive = logger.isAdditive();
        logger.setAdditive(false);
        try {
            sim.host().on(List.of(new Notify("NOPE-1", new Switchover(Role.ACTIVE)),
                    new Notify("RAD-N", new Switchover(Role.STANDALONE))));

            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
            assertThat(receiver.awaitPdus(1, ofSeconds(3))).hasSize(1);
        } finally {
            logger.setAdditive(additive);
            logger.detachAppender(appender);
        }
    }
}
