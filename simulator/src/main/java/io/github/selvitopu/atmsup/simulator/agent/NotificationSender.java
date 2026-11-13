package io.github.selvitopu.atmsup.simulator.agent;

import static io.github.selvitopu.atmsup.mib.AtmTsOids.ALARM_INSTANCE_NAME;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.ALARM_SEVERITY;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.ALARM_TEXT;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_ID;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_ROLE;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_STATUS;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.NOTIF_ALARM_CLEARED;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.NOTIF_ALARM_CODE;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.NOTIF_ALARM_INSTANCE;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.NOTIF_ALARM_RAISED;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.NOTIF_EQ_STATUS_CHANGE;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.NOTIF_SWITCHOVER;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.scalar;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.TrapTarget;
import io.github.selvitopu.atmsup.simulator.model.ActiveAlarm;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.AlarmCleared;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.AlarmRaised;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.StatusChange;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.Switchover;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.UserTarget;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.event.ResponseListener;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;

/**
 * Sends a device's notifications to the supervisor as SNMPv3 authPriv messages from the agent's own engine, as user
 * {@code monitor}: a TRAP, or an acknowledged INFORM for critical alarms (timeout 2000 ms, 3 retries, asynchronous).
 * While dropping, nothing is sent, as if the trap path were broken.
 */
public final class NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(NotificationSender.class);
    private static final long INFORM_TIMEOUT_MS = 2000;
    private static final int INFORM_RETRIES = 3;
    private static final Duration WARN_INTERVAL = Duration.ofMinutes(1);

    private final DeviceAgent agent;
    private final Device device;
    private final TrapTarget trapTarget;
    private final InstantSource clock;
    private final AtomicLong informsAcknowledged = new AtomicLong();
    private final AtomicLong informsFailed = new AtomicLong();
    private volatile boolean dropping;
    private Instant lastUnresolvedWarning;

    public NotificationSender(DeviceAgent agent, Device device, TrapTarget trapTarget, InstantSource clock) {
        this.agent = agent;
        this.device = device;
        this.clock = clock;
        this.trapTarget = trapTarget;
    }

    /**
     * The target is resolved on every send, so a trap host that does not resolve yet (the supervisor container may
     * start after the simulator) never fails the sender for good.
     */
    private UserTarget<UdpAddress> resolveTarget() {
        InetAddress address;
        try {
            address = InetAddress.getByName(trapTarget.host());
        } catch (UnknownHostException e) {
            warnUnresolved();
            return null;
        }
        UserTarget<UdpAddress> target = new UserTarget<>();
        target.setAddress(new UdpAddress(address, trapTarget.port()));
        target.setVersion(SnmpConstants.version3);
        target.setSecurityLevel(SecurityLevel.AUTH_PRIV);
        target.setSecurityName(new OctetString("monitor"));
        target.setTimeout(INFORM_TIMEOUT_MS);
        target.setRetries(INFORM_RETRIES);
        return target;
    }

    private synchronized void warnUnresolved() {
        Instant now = clock.instant();
        if (lastUnresolvedWarning == null || now.isAfter(lastUnresolvedWarning.plus(WARN_INTERVAL))) {
            lastUnresolvedWarning = now;
            log.warn("Device {} cannot resolve trap target {}; notifications are lost until it resolves",
                    device.id(), trapTarget.host());
        }
    }

    /** While true, {@link #send} sends nothing. */
    public void setDropping(boolean dropping) {
        this.dropping = dropping;
    }

    public long informsAcknowledged() {
        return informsAcknowledged.get();
    }

    public long informsFailed() {
        return informsFailed.get();
    }

    public void send(DeviceNotification notification) {
        if (dropping) {
            return;
        }
        Snmp snmp = agent.snmp();
        if (snmp == null) {
            throw new IllegalStateException("Agent " + device.id() + " is not running");
        }
        UserTarget<UdpAddress> target = resolveTarget();
        if (target == null) {
            return;
        }
        boolean inform = notification.inform();
        ScopedPDU pdu = pdu(notification, inform);
        try {
            if (inform) {
                snmp.send(pdu, target, null, new InformListener(snmp));
            } else {
                snmp.send(pdu, target);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Device " + device.id() + " cannot send " + notification, e);
        }
    }

    private ScopedPDU pdu(DeviceNotification notification, boolean inform) {
        ScopedPDU pdu = new ScopedPDU();
        pdu.setType(inform ? PDU.INFORM : PDU.TRAP);
        long upMillis = Duration.between(agent.startedAt(), clock.instant()).toMillis();
        pdu.add(new VariableBinding(SnmpConstants.sysUpTime, new TimeTicks(Math.max(0, upMillis) / 10)));
        pdu.add(new VariableBinding(SnmpConstants.snmpTrapOID, trapOid(notification)));
        pdu.add(new VariableBinding(scalar(EQ_ID), new OctetString(device.id())));
        switch (notification) {
            case StatusChange n -> {
                pdu.add(new VariableBinding(scalar(EQ_STATUS), new Integer32(n.status().code())));
                pdu.add(new VariableBinding(scalar(EQ_ROLE), new Integer32(n.role().code())));
            }
            case AlarmRaised n -> {
                ActiveAlarm a = n.alarm();
                pdu.add(new VariableBinding(scalar(NOTIF_ALARM_CODE), new Integer32(a.code())));
                pdu.add(new VariableBinding(scalar(NOTIF_ALARM_INSTANCE), new Integer32(a.instance())));
                pdu.add(new VariableBinding(row(ALARM_INSTANCE_NAME, a.code(), a.instance()),
                        new OctetString(a.instanceName())));
                pdu.add(new VariableBinding(row(ALARM_SEVERITY, a.code(), a.instance()),
                        new Integer32(a.severity().code())));
                pdu.add(new VariableBinding(row(ALARM_TEXT, a.code(), a.instance()), new OctetString(a.text())));
            }
            case AlarmCleared n -> {
                pdu.add(new VariableBinding(scalar(NOTIF_ALARM_CODE), new Integer32(n.code())));
                pdu.add(new VariableBinding(scalar(NOTIF_ALARM_INSTANCE), new Integer32(n.instance())));
            }
            case Switchover n -> pdu.add(new VariableBinding(scalar(EQ_ROLE), new Integer32(n.role().code())));
        }
        return pdu;
    }

    private static OID trapOid(DeviceNotification notification) {
        return switch (notification) {
            case StatusChange n -> NOTIF_EQ_STATUS_CHANGE;
            case AlarmRaised n -> NOTIF_ALARM_RAISED;
            case AlarmCleared n -> NOTIF_ALARM_CLEARED;
            case Switchover n -> NOTIF_SWITCHOVER;
        };
    }

    private static OID row(OID column, int code, int instance) {
        return new OID(column).append(code).append(instance);
    }

    private final class InformListener implements ResponseListener {
        private final Snmp snmp;

        InformListener(Snmp snmp) {
            this.snmp = snmp;
        }

        @Override
        @SuppressWarnings("rawtypes")
        public void onResponse(ResponseEvent event) {
            snmp.cancel(event.getRequest(), this);
            PDU response = event.getResponse();
            if (response != null && response.getType() == PDU.RESPONSE && response.getErrorStatus() == PDU.noError) {
                informsAcknowledged.incrementAndGet();
            } else {
                informsFailed.incrementAndGet();
                log.warn("Device {} inform to {} failed: {}", device.id(), event.getPeerAddress(),
                        response == null ? "no acknowledgement" : response);
            }
        }
    }
}
