package io.github.selvitopu.atmsup.simulator.fixture;

import java.io.IOException;
import java.io.UncheckedIOException;
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
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

/**
 * A minimal SNMPv3 authPriv (SHA-256, AES-128) manager for tests. Like the agents, it builds its own engine stack and
 * never touches the SNMP4J singletons, so several managers and agents can share one JVM. Every request method returns
 * the response PDU (which may be a REPORT), or {@code null} on timeout.
 */
public final class TestManager implements AutoCloseable {

    private static final long TIMEOUT_MS = 1000;

    private final Snmp snmp;
    private final UserTarget<UdpAddress> target;

    public TestManager(String host, int port, String user, String authPw, String privPw) {
        this(host, port, user, authPw, privPw, 0);
    }

    public TestManager(String host, int port, String user, String authPw, String privPw, int retries) {
        SecurityProtocols protocols = new SecurityProtocols(SecurityProtocols.SecurityProtocolSet.none);
        protocols.addAuthenticationProtocol(new AuthHMAC192SHA256());
        protocols.addPrivacyProtocol(new PrivAES128());
        byte[] localEngineId = MPv3.createLocalEngineID();
        CounterSupport counters = new CounterSupport();
        USM usm = new USM(protocols, new OctetString(localEngineId), 0, counters);
        usm.addUser(new UsmUser(new OctetString(user), AuthHMAC192SHA256.ID, new OctetString(authPw),
                PrivAES128.ID, new OctetString(privPw)));
        MessageDispatcherImpl dispatcher = new MessageDispatcherImpl();
        dispatcher.addMessageProcessingModel(new MPv3(localEngineId, null, protocols,
                SecurityModels.getCollection(new SecurityModel[] {usm}), counters));
        try {
            snmp = new Snmp(dispatcher, new DefaultUdpTransportMapping());
            snmp.setCounterSupport(counters);
            snmp.listen();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        target = new UserTarget<>();
        target.setAddress(new UdpAddress(host + "/" + port));
        target.setVersion(SnmpConstants.version3);
        target.setSecurityLevel(SecurityLevel.AUTH_PRIV);
        target.setSecurityName(new OctetString(user));
        target.setTimeout(TIMEOUT_MS);
        target.setRetries(retries);
    }

    public PDU get(OID... oids) {
        return send(pdu(PDU.GET, oids));
    }

    public PDU getNext(OID oid) {
        return send(pdu(PDU.GETNEXT, oid));
    }

    public PDU getBulk(int nonRepeaters, int maxRepetitions, OID... oids) {
        ScopedPDU pdu = pdu(PDU.GETBULK, oids);
        pdu.setNonRepeaters(nonRepeaters);
        pdu.setMaxRepetitions(maxRepetitions);
        return send(pdu);
    }

    public PDU set(VariableBinding... bindings) {
        ScopedPDU pdu = new ScopedPDU();
        pdu.setType(PDU.SET);
        pdu.addAll(bindings);
        return send(pdu);
    }

    @Override
    public void close() {
        try {
            snmp.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ScopedPDU pdu(int type, OID... oids) {
        ScopedPDU pdu = new ScopedPDU();
        pdu.setType(type);
        for (OID oid : oids) {
            pdu.add(new VariableBinding(oid));
        }
        return pdu;
    }

    private PDU send(PDU pdu) {
        try {
            ResponseEvent<UdpAddress> event = snmp.send(pdu, target);
            return event == null ? null : event.getResponse();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
