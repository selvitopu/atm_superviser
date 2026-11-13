package io.github.selvitopu.atmsup.simulator.agent;

import static io.github.selvitopu.atmsup.mib.AtmTsOids.ALARM_INSTANCE_NAME;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.ALARM_SEVERITY;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.ALARM_TEXT;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_ID;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_ROLE;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_STATUS;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.NOTIF_ALARM_CODE;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.NOTIF_ALARM_INSTANCE;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.scalar;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.DeviceType;
import io.github.selvitopu.atmsup.inventory.Group;
import io.github.selvitopu.atmsup.inventory.SnmpProperties;
import io.github.selvitopu.atmsup.inventory.TrapTarget;
import io.github.selvitopu.atmsup.inventory.UserCredentials;
import io.github.selvitopu.atmsup.mib.AtmTsOids;
import io.github.selvitopu.atmsup.mib.EquipmentStatus;
import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.simulator.fixture.FreePorts;
import io.github.selvitopu.atmsup.simulator.fixture.TestTrapReceiver;
import io.github.selvitopu.atmsup.simulator.model.ActiveAlarm;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.AlarmCleared;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.AlarmRaised;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.StatusChange;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.Switchover;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.snmp4j.PDU;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;

class NotificationSenderIT {

    private static final String HOST = "127.0.0.1";
    private static final SnmpProperties CREDS = new SnmpProperties(
            new UserCredentials("monitor-auth-demo", "monitor-priv-demo"),
            new UserCredentials("operator-auth-demo", "operator-priv-demo"));
    private static final Device DEVICE = new Device("RAD-N", DeviceType.RADAR, "Radar North", Group.SURVEILLANCE,
            HOST, 0, null, Duration.ofSeconds(5));

    private final Instant now = Instant.parse("2026-11-13T10:00:00Z");
    private TestTrapReceiver receiver;
    private DeviceAgent agent;
    private NotificationSender sender;

    @BeforeEach
    void start() {
        int trapPort = FreePorts.udp(1)[0];
        receiver = new TestTrapReceiver(trapPort, CREDS, List.of(DEVICE));
        agent = new DeviceAgent(DEVICE, CREDS, 1, HOST, 0, () -> MibView.builder().build(), SetHandler.READ_ONLY);
        agent.start();
        // 5.25 s after the agent started
        InstantSource clock = InstantSource.fixed(agent.startedAt().plusMillis(5250));
        sender = new NotificationSender(agent, DEVICE, new TrapTarget(HOST, trapPort), clock);
    }

    @AfterEach
    void stop() {
        agent.close();
        receiver.close();
    }

    private PDU single() {
        List<PDU> pdus = receiver.awaitPdus(1, ofSeconds(2));
        assertThat(pdus).hasSize(1);
        return pdus.get(0);
    }

    private static DeviceNotification statusChange() {
        return new StatusChange(EquipmentStatus.FAILED, Role.STANDALONE);
    }

    @Test
    void statusChangeArrivesAsV3Trap() {
        sender.send(statusChange());

        PDU p = single();
        assertThat(p.getType()).isEqualTo(PDU.TRAP);
        assertThat(p.getVariable(SnmpConstants.snmpTrapOID)).isEqualTo(AtmTsOids.NOTIF_EQ_STATUS_CHANGE);
        assertThat(p.getVariable(scalar(EQ_STATUS))).isEqualTo(new Integer32(3));
        assertThat(p.getVariable(scalar(EQ_ROLE))).isEqualTo(new Integer32(Role.STANDALONE.code()));
        assertThat(p.getVariable(scalar(EQ_ID))).isEqualTo(new OctetString("RAD-N"));
        assertThat(p.get(0).getOid()).isEqualTo(SnmpConstants.sysUpTime);
        assertThat(p.get(1).getOid()).isEqualTo(SnmpConstants.snmpTrapOID);
        assertThat(p.getVariable(SnmpConstants.sysUpTime)).isEqualTo(new TimeTicks(525));
    }

    @Test
    void alarmRaisedCarriesTheAlarmRow() {
        sender.send(new AlarmRaised(new ActiveAlarm(201, 2, "Sensor 2", Severity.MINOR, "Input degraded", now)));

        PDU p = single();
        assertThat(p.getType()).isEqualTo(PDU.TRAP);
        assertThat(p.getVariable(SnmpConstants.snmpTrapOID)).isEqualTo(AtmTsOids.NOTIF_ALARM_RAISED);
        assertThat(p.getVariable(scalar(NOTIF_ALARM_CODE))).isEqualTo(new Integer32(201));
        assertThat(p.getVariable(scalar(NOTIF_ALARM_INSTANCE))).isEqualTo(new Integer32(2));
        assertThat(p.getVariable(row(ALARM_INSTANCE_NAME, 201, 2))).isEqualTo(new OctetString("Sensor 2"));
        assertThat(p.getVariable(row(ALARM_SEVERITY, 201, 2))).isEqualTo(new Integer32(Severity.MINOR.code()));
        assertThat(p.getVariable(row(ALARM_TEXT, 201, 2))).isEqualTo(new OctetString("Input degraded"));
    }

    @Test
    void alarmClearedAndSwitchoverCarryTheirVarbinds() {
        sender.send(new AlarmCleared(201, 2));
        sender.send(new Switchover(Role.ACTIVE));

        List<PDU> pdus = receiver.awaitPdus(2, ofSeconds(2));
        assertThat(pdus).hasSize(2);
        PDU cleared = pdus.get(0);
        assertThat(cleared.getVariable(SnmpConstants.snmpTrapOID)).isEqualTo(AtmTsOids.NOTIF_ALARM_CLEARED);
        assertThat(cleared.getVariable(scalar(NOTIF_ALARM_CODE))).isEqualTo(new Integer32(201));
        assertThat(cleared.getVariable(scalar(NOTIF_ALARM_INSTANCE))).isEqualTo(new Integer32(2));
        PDU switchover = pdus.get(1);
        assertThat(switchover.getVariable(SnmpConstants.snmpTrapOID)).isEqualTo(AtmTsOids.NOTIF_SWITCHOVER);
        assertThat(switchover.getVariable(scalar(EQ_ROLE))).isEqualTo(new Integer32(Role.ACTIVE.code()));
    }

    @Test
    void criticalAlarmIsAnAcknowledgedInform() {
        sender.send(new AlarmRaised(new ActiveAlarm(101, 0, "", Severity.CRITICAL, "Equipment failed", now)));

        assertThat(single().getType()).isEqualTo(PDU.INFORM);
        await().atMost(ofSeconds(3)).until(() -> sender.informsAcknowledged() == 1);
        assertThat(sender.informsFailed()).isZero();
    }

    @Test
    void nonCriticalAlarmIsNotAnInform() {
        assertThat(new AlarmRaised(new ActiveAlarm(201, 0, "", Severity.MAJOR, "t", now)).inform()).isFalse();
        assertThat(new AlarmCleared(101, 0).inform()).isFalse();
        assertThat(statusChange().inform()).isFalse();
    }

    @Test
    void droppingSendsNothing() {
        sender.setDropping(true);
        sender.send(statusChange());

        assertThat(receiver.awaitPdus(1, ofSeconds(1))).isEmpty();
    }

    @Test
    void sendingResumesAfterDropping() {
        sender.setDropping(true);
        sender.send(statusChange());
        sender.setDropping(false);
        sender.send(statusChange());

        assertThat(receiver.awaitPdus(2, ofSeconds(1))).hasSize(1);
    }

    @Test
    void unresolvableTrapHostDoesNotThrowAndWarnsOncePerMinute() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(NotificationSender.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        boolean additive = logger.isAdditive();
        logger.setAdditive(false); // keep the expected WARN out of the test console
        try {
            NotificationSender lost = new NotificationSender(agent, DEVICE,
                    new TrapTarget("no-such-host.invalid", 10162), InstantSource.fixed(now));

            lost.send(statusChange());
            lost.send(statusChange());

            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
            assertThat(appender.list.get(0).getFormattedMessage()).contains("no-such-host.invalid");
        } finally {
            logger.setAdditive(additive);
            logger.detachAppender(appender);
        }
    }

    private static OID row(OID column, int code, int instance) {
        return new OID(column).append(code).append(instance);
    }
}
