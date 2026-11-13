package io.github.selvitopu.atmsup.simulator.agent;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.EngineIds;
import io.github.selvitopu.atmsup.inventory.SnmpProperties;
import io.github.selvitopu.atmsup.inventory.UserCredentials;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.function.Supplier;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.SNMP4JSettings;
import org.snmp4j.SNMP4JSettings.ReportSecurityLevelStrategy;
import org.snmp4j.Snmp;
import org.snmp4j.mp.CounterSupport;
import org.snmp4j.mp.DefaultCounterListener;
import org.snmp4j.mp.MPv3;
import org.snmp4j.security.AuthHMAC192SHA256;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.SecurityModel;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.transport.DefaultUdpTransportMapping;

/**
 * The SNMPv3 agent of one simulated device: its own UDP port and its own authoritative engine (engine ID from the
 * device id, the given {@code snmpEngineBoots}), users {@code monitor} and {@code operator} (authPriv, SHA-256 and
 * AES-128). Many agents share one JVM, so the whole engine stack is built per agent and the SNMP4J singletons
 * ({@code SecurityProtocols.getInstance()}, {@code SecurityModels.getInstance()}, {@code CounterSupport.getInstance()})
 * are never used.
 */
public final class DeviceAgent implements AutoCloseable {

    static {
        // RFC 3414 section 3.2: reports about a failed security check (wrong digest, unknown user or engine,
        // unsupported level) go out noAuthNoPriv, so that a manager with wrong keys can still read them;
        // notInTimeWindow reports stay authNoPriv whatever this setting. SNMP4J offers this only JVM-wide, and
        // every engine in the simulator wants it. It is a protocol rule, not shared security state.
        SNMP4JSettings.setReportSecurityLevelStrategy(ReportSecurityLevelStrategy.noAuthNoPrivIfNeeded);
    }

    private final Device device;
    private final SnmpProperties creds;
    private final int engineBoots;
    private final String bindHost;
    private final int port;
    private final OctetString engineId;
    private final DeviceResponder responder;

    private volatile Snmp snmp;
    private volatile int boundPort;
    private volatile Instant startedAt;

    /** {@code port} 0 binds any free port; see {@link #boundPort()}. */
    public DeviceAgent(Device device, SnmpProperties creds, int engineBoots, String bindHost, int port,
                       Supplier<MibView> view, SetHandler setHandler) {
        this.device = device;
        this.creds = creds;
        this.engineBoots = engineBoots;
        this.bindHost = bindHost;
        this.port = port;
        this.engineId = new OctetString(EngineIds.of(device.id()));
        this.responder = new DeviceResponder(device.id(), view, setHandler);
    }

    /** Starts the engine once; a restart is a new {@code DeviceAgent} with higher {@code engineBoots}. */
    public synchronized void start() {
        if (startedAt != null) {
            throw new IllegalStateException("Agent " + device.id() + " has already been started");
        }
        SecurityProtocols protocols = new SecurityProtocols(SecurityProtocols.SecurityProtocolSet.none);
        protocols.addAuthenticationProtocol(new AuthHMAC192SHA256());
        protocols.addPrivacyProtocol(new PrivAES128());
        CounterSupport counters = new CounterSupport();
        counters.addCounterListener(new DefaultCounterListener());
        USM usm = new USM(protocols, engineId, engineBoots, counters);
        usm.addUser(user("monitor", creds.monitor()));
        usm.addUser(user("operator", creds.operator()));
        MessageDispatcherImpl dispatcher = new MessageDispatcherImpl();
        dispatcher.addMessageProcessingModel(new MPv3(engineId.getValue(), null, protocols,
                SecurityModels.getCollection(new SecurityModel[] {usm}), counters));
        try {
            var transport = new DefaultUdpTransportMapping(new UdpAddress(bindHost + "/" + port));
            snmp = new Snmp(dispatcher, transport);
            snmp.setCounterSupport(counters);
            snmp.addCommandResponder(responder);
            snmp.listen();
            boundPort = transport.getListenAddress().getPort();
        } catch (IOException e) {
            close();
            throw new UncheckedIOException("Cannot start agent " + device.id() + " on " + bindHost + "/" + port, e);
        }
        startedAt = Instant.now();
    }

    @Override
    public synchronized void close() {
        Snmp running = snmp;
        snmp = null;
        if (running != null) {
            try {
                running.close();
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot stop agent " + device.id(), e);
            }
        }
    }

    /** The UDP port bound by {@link #start()}, which differs from the constructor's {@code port} when that was 0. */
    public int boundPort() {
        if (startedAt == null) {
            throw new IllegalStateException("Agent " + device.id() + " has not been started");
        }
        return boundPort;
    }

    /** While false, requests are silently dropped, as if the device were unreachable. */
    public void setResponding(boolean responding) {
        responder.setResponding(responding);
    }

    /** The running engine (for sending notifications), or null when not started or closed. */
    public Snmp snmp() {
        return snmp;
    }

    public OctetString engineId() {
        return new OctetString(engineId);
    }

    /** When the agent last started, the base of its sysUpTime; null before {@link #start()}. */
    public Instant startedAt() {
        return startedAt;
    }

    private static UsmUser user(String name, UserCredentials credentials) {
        // no engine ID: the USM localises the keys to this agent's engine
        return new UsmUser(new OctetString(name), AuthHMAC192SHA256.ID, new OctetString(credentials.authPassword()),
                PrivAES128.ID, new OctetString(credentials.privPassword()));
    }
}
