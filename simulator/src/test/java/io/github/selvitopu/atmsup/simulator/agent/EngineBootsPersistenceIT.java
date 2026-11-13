package io.github.selvitopu.atmsup.simulator.agent;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.DeviceType;
import io.github.selvitopu.atmsup.inventory.Group;
import io.github.selvitopu.atmsup.inventory.SnmpProperties;
import io.github.selvitopu.atmsup.inventory.TrapTarget;
import io.github.selvitopu.atmsup.inventory.UserCredentials;
import io.github.selvitopu.atmsup.mib.EquipmentStatus;
import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.simulator.fixture.FreePorts;
import io.github.selvitopu.atmsup.simulator.fixture.TestTrapReceiver;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.StatusChange;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Hazard H8: a receiver remembers the highest {@code snmpEngineBoots} it has seen per sender engine and, per RFC 3414,
 * refuses authenticated messages from a lower boots count (outside the time window). So a device that restarts must
 * come back with higher boots, which is why {@code snmpEngineBoots} is persisted.
 */
class EngineBootsPersistenceIT {

    private static final String HOST = "127.0.0.1";
    private static final SnmpProperties CREDS = new SnmpProperties(
            new UserCredentials("monitor-auth-demo", "monitor-priv-demo"),
            new UserCredentials("operator-auth-demo", "operator-priv-demo"));
    private static final Device DEVICE = new Device("RAD-N", DeviceType.RADAR, "Radar North", Group.SURVEILLANCE,
            HOST, 0, null, Duration.ofSeconds(5));

    private TestTrapReceiver receiver;
    private TrapTarget target;
    private DeviceAgent agent;

    @BeforeEach
    void startReceiver() {
        int trapPort = FreePorts.udp(1)[0];
        receiver = new TestTrapReceiver(trapPort, CREDS, List.of(DEVICE));
        target = new TrapTarget(HOST, trapPort);
    }

    @AfterEach
    void stop() {
        if (agent != null) {
            agent.close();
        }
        receiver.close();
    }

    private void startAgent(int boots) {
        agent = new DeviceAgent(DEVICE, CREDS, boots, HOST, 0, () -> MibView.builder().build(), SetHandler.READ_ONLY);
        agent.start();
    }

    private void sendStatusChange() {
        new NotificationSender(agent, DEVICE, target, InstantSource.system())
                .send(new StatusChange(EquipmentStatus.FAILED, Role.STANDALONE));
    }

    /** Stops the running agent and starts a new engine of the same device with the given boots. */
    private void restartWithBoots(int boots) {
        agent.close();
        startAgent(boots);
    }

    @Test
    void trapAfterRestartWithHigherBootsIsAccepted() {
        startAgent(5);
        sendStatusChange();
        assertThat(receiver.awaitPdus(1, ofSeconds(2))).hasSize(1);

        restartWithBoots(6);
        sendStatusChange();

        assertThat(receiver.awaitPdus(1, ofSeconds(2))).hasSize(1);
    }

    @Test
    void trapAfterRestartWithLowerBootsIsRejected() {
        startAgent(5);
        sendStatusChange();
        assertThat(receiver.awaitPdus(1, ofSeconds(2))).hasSize(1);

        restartWithBoots(1);
        sendStatusChange();

        // the receiver has seen boots 5 for this engine; a trap claiming boots 1 is outside the time window
        assertThat(receiver.awaitPdus(1, ofSeconds(2))).isEmpty();
    }
}
