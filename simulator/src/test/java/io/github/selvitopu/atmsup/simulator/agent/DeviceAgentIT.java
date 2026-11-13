package io.github.selvitopu.atmsup.simulator.agent;

import static io.github.selvitopu.atmsup.mib.AtmTsOids.ALARM_SEVERITY;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_ROLE;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_STATUS;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.scalar;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.DeviceType;
import io.github.selvitopu.atmsup.inventory.Group;
import io.github.selvitopu.atmsup.inventory.SnmpProperties;
import io.github.selvitopu.atmsup.inventory.UserCredentials;
import io.github.selvitopu.atmsup.mib.AtmTsOids;
import io.github.selvitopu.atmsup.simulator.fixture.TestManager;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.snmp4j.PDU;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.Null;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.VariableBinding;

class DeviceAgentIT {

    private static final String B = AtmTsOids.ROOT.toDottedString();
    private static final String HOST = "127.0.0.1";
    private static final UserCredentials MONITOR = new UserCredentials("monitor-auth-demo", "monitor-priv-demo");
    private static final SnmpProperties CREDS =
            new SnmpProperties(MONITOR, new UserCredentials("operator-auth-demo", "operator-priv-demo"));
    private static final Device DEVICE = new Device("RAD-N", DeviceType.RADAR, "Radar North", Group.SURVEILLANCE,
            HOST, 0, null, Duration.ofSeconds(5));
    private static final int FILLERS = 2000;
    private static final OID LAST_FILLER = new OID(B + ".1.9.99." + FILLERS + ".0");
    private static final MibView VIEW = view();

    private DeviceAgent agent;
    private TestManager mgr;

    private static MibView view() {
        MibView.Builder view = MibView.builder()
                .put(SnmpConstants.sysName, new OctetString("RAD-N"))
                .put(scalar(EQ_STATUS), new Integer32(1))
                .put(scalar(EQ_ROLE), new Integer32(1))
                .put(severity(101), new Integer32(3))
                .put(severity(201), new Integer32(5));
        for (int i = 1; i <= FILLERS; i++) {
            view.put(new OID(B + ".1.9.99." + i + ".0"), new Integer32(i));
        }
        return view.build();
    }

    private static OID severity(int alarmCode) {
        return new OID(ALARM_SEVERITY).append(alarmCode).append(0);
    }

    private static DeviceAgent agent(int boots, int port) {
        return agent(boots, port, () -> VIEW);
    }

    private static DeviceAgent agent(int boots, int port, Supplier<MibView> view) {
        DeviceAgent agent = new DeviceAgent(DEVICE, CREDS, boots, HOST, port, view, SetHandler.READ_ONLY);
        agent.start();
        return agent;
    }

    private TestManager manager(String authPw, int retries) {
        return new TestManager(HOST, agent.boundPort(), "monitor", authPw, MONITOR.privPassword(), retries);
    }

    @BeforeEach
    void startAgent() {
        agent = agent(1, 0);
        mgr = manager(MONITOR.authPassword(), 0);
    }

    @AfterEach
    void stopAgent() {
        mgr.close();
        agent.close();
    }

    @Test
    void getReturnsValue() {
        PDU r = mgr.get(scalar(EQ_STATUS));

        assertThat(r.getType()).isEqualTo(PDU.RESPONSE);
        assertThat(r.getErrorStatus()).isEqualTo(PDU.noError);
        assertThat(r.get(0).getVariable()).isEqualTo(new Integer32(1));
    }

    @Test
    void missingInstanceIsNoSuchInstance() {
        assertThat(mgr.get(new OID(EQ_STATUS).append(1)).get(0).getVariable()).isEqualTo(Null.noSuchInstance);
    }

    @Test
    void missingObjectIsNoSuchObject() {
        assertThat(mgr.get(new OID(B + ".1.1.99.0")).get(0).getVariable()).isEqualTo(Null.noSuchObject);
    }

    @Test
    void getNextPastEndIsEndOfMibView() {
        VariableBinding vb = mgr.getNext(LAST_FILLER).get(0);

        assertThat(vb.getOid()).isEqualTo(LAST_FILLER);
        assertThat(vb.getVariable()).isEqualTo(Null.endOfMibView);
    }

    @Test
    void getBulkHonoursNonRepeatersAndMaxRepetitions() {
        PDU r = mgr.getBulk(1, 2, SnmpConstants.sysName, ALARM_SEVERITY);

        assertThat(r.getErrorStatus()).isEqualTo(PDU.noError);
        assertThat(r.getVariableBindings()).extracting(VariableBinding::getOid)
                .containsExactly(scalar(EQ_STATUS), severity(101), severity(201));
        assertThat(r.getVariableBindings()).extracting(VariableBinding::getVariable)
                .containsExactly(new Integer32(1), new Integer32(3), new Integer32(5));
    }

    @Test
    void getBulkStopsBeforeUdpSize() {
        PDU r = mgr.getBulk(0, 1000, new OID(B + ".1.9.99"));

        assertThat(r.getErrorStatus()).isEqualTo(PDU.noError);
        assertThat(r.size()).isBetween(1, 999);
        assertThat(r.getBERLength()).isLessThanOrEqualTo(1472);
    }

    @Test
    void setIsRejectedAsNotWritableByTheReadOnlyHandler() {
        VariableBinding vb = new VariableBinding(scalar(EQ_STATUS), new Integer32(2));

        PDU r = mgr.set(vb);

        assertThat(r.getErrorStatus()).isEqualTo(PDU.notWritable);
        assertThat(r.getErrorIndex()).isEqualTo(1);
        assertThat(r.toArray()).containsExactly(vb);
        assertThat(mgr.get(scalar(EQ_STATUS)).get(0).getVariable()).isEqualTo(new Integer32(1));
    }

    @Test
    void wrongAuthPasswordGetsUsmReport() {
        try (TestManager wrongPwMgr = manager("wrong-auth-password", 0)) {
            PDU r = wrongPwMgr.get(scalar(EQ_STATUS));

            assertThat(r.getType()).isEqualTo(PDU.REPORT);
            assertThat(r.get(0).getOid()).isEqualTo(SnmpConstants.usmStatsWrongDigests);
        }
    }

    @Test
    void noAuthNoPrivRequestIsRejected() {
        try (TestManager noAuthMgr = new TestManager(HOST, agent.boundPort(), "monitor", MONITOR.authPassword(),
                MONITOR.privPassword(), 0, SecurityLevel.NOAUTH_NOPRIV)) {
            PDU r = noAuthMgr.get(scalar(EQ_STATUS));

            assertThat(r.getType()).isEqualTo(PDU.RESPONSE);
            assertThat(r.getErrorStatus()).isEqualTo(PDU.authorizationError);
            assertThat(r.getErrorIndex()).isZero();
            assertThat(r.get(0).getOid()).isEqualTo(scalar(EQ_STATUS));
            assertThat(r.get(0).getVariable()).isEqualTo(new Null());
        }
    }

    @Test
    void failingViewAnswersGenErrAndLogsTheError() throws InterruptedException {
        BlockingQueue<ILoggingEvent> logged = new LinkedBlockingQueue<>();
        Logger responderLog = (Logger) LoggerFactory.getLogger(DeviceResponder.class);
        AppenderBase<ILoggingEvent> capture = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                logged.add(event);
            }
        };
        capture.start();
        responderLog.addAppender(capture);
        responderLog.setAdditive(false); // the expected error is asserted here, not printed
        Supplier<MibView> broken = () -> {
            throw new IllegalStateException("model bug");
        };
        try (DeviceAgent failing = agent(1, 0, broken);
             TestManager failingMgr = new TestManager(HOST, failing.boundPort(), "monitor",
                     MONITOR.authPassword(), MONITOR.privPassword())) {
            PDU r = failingMgr.get(scalar(EQ_STATUS));

            assertThat(r.getType()).isEqualTo(PDU.RESPONSE);
            assertThat(r.getErrorStatus()).isEqualTo(PDU.genErr);
            assertThat(r.getErrorIndex()).isEqualTo(1);
            assertThat(r.toArray()).containsExactly(new VariableBinding(scalar(EQ_STATUS)));
            ILoggingEvent error = logged.poll(1, TimeUnit.SECONDS);
            assertThat(error).isNotNull();
            assertThat(error.getLevel()).isEqualTo(Level.ERROR);
            assertThat(error.getFormattedMessage()).contains("RAD-N", "GET");
            assertThat(error.getThrowableProxy().getMessage()).isEqualTo("model bug");
        } finally {
            responderLog.detachAppender(capture);
            responderLog.setAdditive(true);
            capture.stop();
        }
    }

    @Test
    void notRespondingDropsRequests() {
        agent.setResponding(false);
        assertThat(mgr.get(scalar(EQ_STATUS))).isNull();

        agent.setResponding(true);
        assertThat(mgr.get(scalar(EQ_STATUS))).isNotNull();
    }

    @Test
    void managerResyncsAfterAgentRestartWithHigherBoots() {
        int port = agent.boundPort();
        assertThat(mgr.get(scalar(EQ_STATUS))).isNotNull();

        agent.close();
        agent = agent(2, port);
        PDU r = mgr.get(scalar(EQ_STATUS));

        assertThat(r).isNotNull();
        assertThat(r.getType()).isEqualTo(PDU.RESPONSE);
        assertThat(r.get(0).getVariable()).isEqualTo(new Integer32(1));
    }
}
