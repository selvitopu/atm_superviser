package io.github.selvitopu.atmsup.supervisor.snmp;

import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_COMMAND;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_ID;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_MAINTENANCE_MODE;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.NOTIF_EQ_STATUS_CHANGE;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.scalar;
import static io.github.selvitopu.atmsup.mib.Severity.CRITICAL;
import static io.github.selvitopu.atmsup.simulator.model.Fault.FAIL;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.selvitopu.atmsup.inventory.EngineIds;
import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.inventory.SnmpProperties;
import io.github.selvitopu.atmsup.simulator.agent.DeviceAgent;
import io.github.selvitopu.atmsup.simulator.fixture.FreePorts;
import io.github.selvitopu.atmsup.simulator.fixture.SimulatorFixture;
import io.github.selvitopu.atmsup.simulator.fixture.TestInventories;
import io.github.selvitopu.atmsup.simulator.fixture.TestManager;
import io.github.selvitopu.atmsup.simulator.model.FaultRequest;
import io.github.selvitopu.atmsup.supervisor.CapturedLogs;
import io.github.selvitopu.atmsup.supervisor.snmp.Notification.AlarmRaisedNotification;
import io.github.selvitopu.atmsup.supervisor.snmp.Notification.StatusChangeNotification;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.UserTarget;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.CounterSupport;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.AuthHMAC192SHA256;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.SecurityModel;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

/** The receiver on a real session, fed by the simulator's agents (and by a hand-built sender for bad input). */
class TrapReceiverIT {

    @TempDir
    Path dataDir;

    private final SnmpProperties creds = TestInventories.demoCredentials();
    private final List<Notification> received = new CopyOnWriteArrayList<>();
    private int firstPort;
    private int trapPort;
    private Inventory inv;
    private SnmpSession session;
    private NotificationMapper mapper;
    private SimulatorFixture fixture;

    @BeforeEach
    void startSession() {
        firstPort = FreePorts.udpBlock(7);
        trapPort = FreePorts.udp(1)[0];
        inv = Inventory.from(TestInventories.v01(firstPort, trapPort));
        mapper = new NotificationMapper(inv);
        session = new SnmpSession(inv, creds, 1, TestInventories.HOST, trapPort);
        session.snmp().addCommandResponder(new TrapReceiver(mapper, received::add, InstantSource.system()));
        session.start();
    }

    @AfterEach
    void stop() {
        if (fixture != null) {
            fixture.close();
        }
        session.close();
    }

    @Test
    void radarFailureArrivesAsNotifications() {
        fixture = SimulatorFixture.start(inv, creds, dataDir);

        fixture.centre().applyFault("RAD-N", new FaultRequest(FAIL, null));

        await().atMost(ofSeconds(3)).untilAsserted(() -> assertThat(received)
                .anyMatch(n -> n instanceof StatusChangeNotification s && s.equipmentId().equals("RAD-N"))
                .anyMatch(n -> n instanceof AlarmRaisedNotification a && a.equipmentId().equals("RAD-N")
                        && a.code() == 101 && a.severity() == CRITICAL));
        await().atMost(ofSeconds(3)).until(() -> fixture.host().sender("RAD-N").informsAcknowledged() >= 1);
    }

    @Test
    void malformedInformIsStillAcknowledged() {
        try (CapturedLogs logs = new CapturedLogs(NotificationMapper.class);
             InformSender sender = new InformSender("RAD-N")) {
            PDU response = sender.sendInform(new OctetString("X-9"));

            assertThat(response).isNotNull();
            assertThat(response.getType()).isEqualTo(PDU.RESPONSE);
            assertThat(response.getErrorStatus()).isEqualTo(PDU.noError);
            assertThat(response.getVariable(scalar(EQ_ID))).isEqualTo(new OctetString("X-9"));
            assertThat(received).isEmpty();
            assertThat(mapper.rejected()).isEqualTo(1);
            assertThat(logs.warnings()).singleElement().asString().contains("unknown equipment 'X-9'");
        }
    }

    @Test
    void noAuthNoPrivNotificationsAreDropped() {
        try (CapturedLogs logs = new CapturedLogs(TrapReceiver.class);
             InformSender sender = new InformSender("RAD-N", SecurityLevel.NOAUTH_NOPRIV)) {
            assertThat(sender.sendInform(new OctetString("RAD-N"))).isNull();
            assertThat(sender.send(PDU.TRAP, new OctetString("RAD-N"))).isNull();
            assertThat(logs.warnings()).hasSize(1).first().asString().contains("Unauthenticated notification");
        }
        assertThat(received).isEmpty();
        assertThat(mapper.rejected()).isZero();
    }

    @Test
    void spoofedEquipmentIdIsRejected() {
        try (CapturedLogs logs = new CapturedLogs(NotificationMapper.class);
             InformSender sender = new InformSender("RAD-E")) {
            PDU response = sender.sendInform(new OctetString("RAD-N"));

            assertThat(response).isNotNull(); // authenticated, so acknowledged even though rejected
            assertThat(received).isEmpty();
            assertThat(mapper.rejected()).isEqualTo(1);
            assertThat(logs.warnings()).singleElement().asString()
                    .contains("engine ID does not match equipment 'RAD-N'");
        }
    }

    @Test
    void trapsStillAcceptedAfterDeviceRestart() throws Exception {
        fixture = SimulatorFixture.start(inv, creds, dataDir, Duration.ofSeconds(1));
        DeviceAgent before = fixture.host().agent("RAD-N");

        try (TestManager operator = new TestManager(TestInventories.HOST, firstPort, "operator",
                creds.operator().authPassword(), creds.operator().privPassword())) {
            assertThat(operator.set(new VariableBinding(scalar(EQ_MAINTENANCE_MODE), new Integer32(1)))
                    .getErrorStatus()).isEqualTo(PDU.noError);
            assertThat(operator.set(new VariableBinding(scalar(EQ_COMMAND), new Integer32(3)))
                    .getErrorStatus()).isEqualTo(PDU.noError);
        }
        await().atMost(ofSeconds(5)).until(() -> {
            DeviceAgent now = fixture.host().agent("RAD-N");
            return now != before && now.snmp() != null;
        });
        received.clear();

        fixture.centre().applyFault("RAD-N", new FaultRequest(FAIL, null));

        await().atMost(ofSeconds(5)).untilAsserted(() -> assertThat(received)
                .anyMatch(n -> n instanceof AlarmRaisedNotification a && a.equipmentId().equals("RAD-N")));
    }

    /** A manager-side engine posing as device {@code deviceId} (so the session accepts it) that sends INFORMs. */
    private final class InformSender implements AutoCloseable {

        private final Snmp snmp;
        private final UserTarget<UdpAddress> target = new UserTarget<>();

        InformSender(String deviceId) {
            this(deviceId, SecurityLevel.AUTH_PRIV);
        }

        InformSender(String deviceId, int securityLevel) {
            SecurityProtocols protocols = new SecurityProtocols(SecurityProtocols.SecurityProtocolSet.none);
            protocols.addAuthenticationProtocol(new AuthHMAC192SHA256());
            protocols.addPrivacyProtocol(new PrivAES128());
            byte[] engineId = EngineIds.of(deviceId);
            CounterSupport counters = new CounterSupport();
            USM usm = new USM(protocols, new OctetString(engineId), 1, counters);
            usm.addUser(new UsmUser(new OctetString("monitor"), AuthHMAC192SHA256.ID,
                    new OctetString(creds.monitor().authPassword()), PrivAES128.ID,
                    new OctetString(creds.monitor().privPassword())));
            MessageDispatcherImpl dispatcher = new MessageDispatcherImpl();
            dispatcher.addMessageProcessingModel(new MPv3(engineId, null, protocols,
                    SecurityModels.getCollection(new SecurityModel[] {usm}), counters));
            try {
                snmp = new Snmp(dispatcher, new DefaultUdpTransportMapping());
                snmp.listen();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            target.setAddress(new UdpAddress(TestInventories.HOST + "/" + trapPort));
            target.setVersion(SnmpConstants.version3);
            target.setSecurityLevel(securityLevel);
            target.setSecurityName(new OctetString("monitor"));
            target.setTimeout(2000);
            target.setRetries(0);
        }

        PDU sendInform(OctetString eqId) {
            return send(PDU.INFORM, eqId);
        }

        PDU send(int type, OctetString eqId) {
            ScopedPDU pdu = new ScopedPDU();
            pdu.setType(type);
            pdu.add(new VariableBinding(SnmpConstants.sysUpTime, new TimeTicks(100)));
            pdu.add(new VariableBinding(SnmpConstants.snmpTrapOID, NOTIF_EQ_STATUS_CHANGE));
            pdu.add(new VariableBinding(scalar(EQ_ID), eqId));
            try {
                ResponseEvent<UdpAddress> event = snmp.send(pdu, target);
                return event == null ? null : event.getResponse();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void close() {
            try {
                snmp.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
