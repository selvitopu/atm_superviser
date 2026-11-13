package io.github.selvitopu.atmsup.supervisor.snmp;

import java.time.InstantSource;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.CommandResponder;
import org.snmp4j.CommandResponderEvent;
import org.snmp4j.PDU;
import org.snmp4j.mp.StatusInformation;
import org.snmp4j.smi.Address;

/**
 * Receives v3 TRAPs and INFORMs on the session's transport. It runs on SNMP4J's single listener thread, which also
 * delivers poll responses, so it only maps, hands the result to the sink and (for an INFORM) returns the RESPONSE:
 * no blocking work. Every INFORM is acknowledged, even if mapping fails or the sink throws. It never throws.
 */
public final class TrapReceiver implements CommandResponder {

    private static final Logger log = LoggerFactory.getLogger(TrapReceiver.class);

    private final NotificationMapper mapper;
    private final Consumer<Notification> sink;
    private final InstantSource clock;

    public TrapReceiver(NotificationMapper mapper, Consumer<Notification> sink, InstantSource clock) {
        this.mapper = mapper;
        this.sink = sink;
        this.clock = clock;
    }

    @Override
    public <A extends Address> void processPdu(CommandResponderEvent<A> event) {
        try {
            PDU pdu = event.getPDU();
            if (pdu == null || (pdu.getType() != PDU.TRAP && pdu.getType() != PDU.INFORM)) {
                return;
            }
            try {
                mapper.map(pdu, clock.instant()).ifPresent(sink);
            } catch (RuntimeException e) {
                log.warn("Cannot process notification from {}", event.getPeerAddress(), e);
            }
            if (pdu.getType() == PDU.INFORM) {
                acknowledge(event, pdu);
            }
            event.setProcessed(true);
        } catch (RuntimeException e) {
            log.warn("Trap receiver failed", e);
        }
    }

    private static <A extends Address> void acknowledge(CommandResponderEvent<A> event, PDU inform) {
        PDU response = (PDU) inform.clone();
        response.setType(PDU.RESPONSE);
        response.setErrorStatus(PDU.noError);
        response.setErrorIndex(0);
        try {
            event.getMessageDispatcher().returnResponsePdu(event.getMessageProcessingModel(),
                    event.getSecurityModel(), event.getSecurityName(), event.getSecurityLevel(), response,
                    event.getMaxSizeResponsePDU(), event.getStateReference(), new StatusInformation());
        } catch (Exception e) {
            log.warn("Cannot acknowledge inform from {}", event.getPeerAddress(), e);
        }
    }
}
