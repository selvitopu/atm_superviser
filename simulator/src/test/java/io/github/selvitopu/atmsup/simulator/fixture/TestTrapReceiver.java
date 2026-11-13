package io.github.selvitopu.atmsup.simulator.fixture;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.EngineIds;
import io.github.selvitopu.atmsup.inventory.SnmpProperties;
import io.github.selvitopu.atmsup.inventory.UserCredentials;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.snmp4j.CommandResponder;
import org.snmp4j.CommandResponderEvent;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.mp.CounterSupport;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.StatusInformation;
import org.snmp4j.security.AuthHMAC192SHA256;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.SecurityModel;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.transport.DefaultUdpTransportMapping;

/**
 * A stand-in for the supervisor's trap receiver: its own SNMPv3 engine ({@code EngineIds.of("SUPERVISOR")}, like the
 * real supervisor), built without SNMP4J singletons. Traps and informs are sent by the devices' engines, which are
 * the authoritative side, so the keys of user {@code monitor} are localised once per sender engine ID; the sender
 * engine IDs are known from the inventory. Informs are acknowledged. Every received trap or inform is kept.
 */
public final class TestTrapReceiver implements AutoCloseable {

    private final Snmp snmp;
    private final BlockingQueue<PDU> received = new LinkedBlockingQueue<>();

    public TestTrapReceiver(int port, SnmpProperties creds, List<Device> senders) {
        UserCredentials monitor = creds.monitor();
        SecurityProtocols protocols = new SecurityProtocols(SecurityProtocols.SecurityProtocolSet.none);
        protocols.addAuthenticationProtocol(new AuthHMAC192SHA256());
        protocols.addPrivacyProtocol(new PrivAES128());
        OctetString engineId = new OctetString(EngineIds.of(EngineIds.SUPERVISOR));
        CounterSupport counters = new CounterSupport();
        USM usm = new USM(protocols, engineId, 0, counters);
        // for informs the receiver is the authoritative engine: this user is localised to the receiver's engine
        usm.addUser(user(monitor));
        // for traps the sender is authoritative: one user per sender engine ID
        for (Device sender : senders) {
            usm.addUser(new OctetString("monitor"), new OctetString(EngineIds.of(sender.id())), user(monitor));
        }
        MessageDispatcherImpl dispatcher = new MessageDispatcherImpl();
        dispatcher.addMessageProcessingModel(new MPv3(engineId.getValue(), null, protocols,
                SecurityModels.getCollection(new SecurityModel[] {usm}), counters));
        try {
            snmp = new Snmp(dispatcher, new DefaultUdpTransportMapping(new UdpAddress("127.0.0.1/" + port)));
            snmp.setCounterSupport(counters);
            snmp.addCommandResponder(new Collector());
            snmp.listen();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot listen for traps on port " + port, e);
        }
    }

    /** Waits until {@code n} PDUs have arrived or the timeout passes; returns what arrived so far (at most {@code n}). */
    public List<PDU> awaitPdus(int n, Duration timeout) {
        List<PDU> got = new ArrayList<>(n);
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (got.size() < n) {
                PDU pdu = received.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                if (pdu == null) {
                    break;
                }
                got.add(pdu);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return got;
    }

    @Override
    public void close() {
        try {
            snmp.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static UsmUser user(UserCredentials credentials) {
        return new UsmUser(new OctetString("monitor"), AuthHMAC192SHA256.ID,
                new OctetString(credentials.authPassword()), PrivAES128.ID, new OctetString(credentials.privPassword()));
    }

    private final class Collector implements CommandResponder {
        @Override
        public <A extends Address> void processPdu(CommandResponderEvent<A> event) {
            PDU pdu = event.getPDU();
            if (pdu == null) {
                return;
            }
            if (pdu.getType() == PDU.TRAP || pdu.getType() == PDU.INFORM) {
                event.setProcessed(true);
                received.add((PDU) pdu.clone());
            }
            if (pdu.getType() == PDU.INFORM) {
                PDU response = (PDU) pdu.clone();
                response.setType(PDU.RESPONSE);
                response.setErrorStatus(PDU.noError);
                response.setErrorIndex(0);
                try {
                    event.getMessageDispatcher().returnResponsePdu(event.getMessageProcessingModel(),
                            event.getSecurityModel(), event.getSecurityName(), event.getSecurityLevel(), response,
                            event.getMaxSizeResponsePDU(), event.getStateReference(), new StatusInformation());
                } catch (Exception e) {
                    throw new IllegalStateException("Cannot acknowledge inform", e);
                }
            }
        }
    }
}
