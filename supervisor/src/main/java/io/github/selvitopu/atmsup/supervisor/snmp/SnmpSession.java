package io.github.selvitopu.atmsup.supervisor.snmp;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.EngineIds;
import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.inventory.SnmpProperties;
import io.github.selvitopu.atmsup.inventory.UserCredentials;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.Snmp;
import org.snmp4j.UserTarget;
import org.snmp4j.mp.CounterSupport;
import org.snmp4j.mp.DefaultCounterListener;
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
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.transport.DefaultUdpTransportMapping;

/**
 * The supervisor's SNMPv3 manager engine ({@code EngineIds.of("SUPERVISOR")}), built without SNMP4J singletons. One UDP
 * transport on {@code bindHost:port} sends the requests and receives the traps.
 *
 * <p>User {@code monitor} is registered twice in the USM. Non-localised, it serves requests: the devices are the
 * authoritative engines and SNMP4J localises the keys to the engine ID it discovers. Per inventory device it is also
 * registered with {@code usm.addUser(userName, deviceEngineId, user)}, which localises the passwords to that engine so
 * that traps sent by the device (which is the authoritative engine for a trap) are accepted. The 6-argument
 * {@code UsmUser} with a localisation engine ID must not be used: it takes the passwords as already localised keys.
 */
public final class SnmpSession implements AutoCloseable {

    static final long TIMEOUT_MS = 1500;
    static final int RETRIES = 1;
    private static final OctetString MONITOR = new OctetString("monitor");
    private static final OctetString OPERATOR = new OctetString("operator");

    private final Snmp snmp;
    private final DefaultUdpTransportMapping transport;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile int boundPort;

    /** {@code port} 0 binds any free port; see {@link #boundPort()}. */
    public SnmpSession(Inventory inv, SnmpProperties creds, int engineBoots, String bindHost, int port) {
        UserCredentials monitor = creds.monitor();
        SecurityProtocols protocols = new SecurityProtocols(SecurityProtocols.SecurityProtocolSet.none);
        protocols.addAuthenticationProtocol(new AuthHMAC192SHA256());
        protocols.addPrivacyProtocol(new PrivAES128());
        OctetString engineId = new OctetString(EngineIds.of(EngineIds.SUPERVISOR));
        CounterSupport counters = new CounterSupport();
        counters.addCounterListener(new DefaultCounterListener());
        USM usm = new USM(protocols, engineId, engineBoots, counters);
        usm.addUser(user(MONITOR, monitor));
        usm.addUser(user(OPERATOR, creds.operator())); // requests only: the devices never send traps as operator
        for (Device d : inv.devices()) {
            usm.addUser(MONITOR, new OctetString(EngineIds.of(d.id())), user(MONITOR, monitor));
        }
        MessageDispatcherImpl dispatcher = new MessageDispatcherImpl();
        dispatcher.addMessageProcessingModel(new MPv3(engineId.getValue(), null, protocols,
                SecurityModels.getCollection(new SecurityModel[] {usm}), counters));
        try {
            transport = new DefaultUdpTransportMapping(new UdpAddress(bindHost + "/" + port));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open SNMP transport on " + bindHost + "/" + port, e);
        }
        snmp = new Snmp(dispatcher, transport);
        snmp.setCounterSupport(counters);
    }

    /** Binds the UDP port and starts receiving. */
    public void start() {
        try {
            snmp.listen();
            boundPort = transport.getListenAddress().getPort();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot listen for SNMP on the supervisor port", e);
        }
    }

    /** The engine, for sending requests and for registering the trap responder. */
    public Snmp snmp() {
        return snmp;
    }

    /** The UDP port bound by {@link #start()}, which differs from the constructor's {@code port} when that was 0. */
    public int boundPort() {
        return boundPort;
    }

    /** A request target for {@code d}: user monitor, authPriv, SNMPv3, 1500 ms timeout and 1 retry. */
    public UserTarget<UdpAddress> target(Device d) {
        return target(d, MONITOR);
    }

    /** The same target as the read-write {@code operator} user, for the commands a supervisor sends. */
    public UserTarget<UdpAddress> operatorTarget(Device d) {
        return target(d, OPERATOR);
    }

    private static UserTarget<UdpAddress> target(Device d, OctetString user) {
        UserTarget<UdpAddress> target = new UserTarget<>();
        target.setAddress(new UdpAddress(d.host() + "/" + d.port()));
        target.setVersion(SnmpConstants.version3);
        target.setSecurityLevel(SecurityLevel.AUTH_PRIV);
        target.setSecurityName(user);
        target.setTimeout(TIMEOUT_MS);
        target.setRetries(RETRIES);
        return target;
    }

    /**
     * Stops the engine. Idempotent: the lifecycle stop and Spring's inferred destroy method both call it, and only the
     * first call acts.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            snmp.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot stop the SNMP session", e);
        }
    }

    private static UsmUser user(OctetString name, UserCredentials credentials) {
        // no engine ID: the keys are localised to the engine the USM talks to (or is given with addUser)
        return new UsmUser(name, AuthHMAC192SHA256.ID, new OctetString(credentials.authPassword()),
                PrivAES128.ID, new OctetString(credentials.privPassword()));
    }
}
