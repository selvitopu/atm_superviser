package io.github.selvitopu.atmsup.supervisor.snmp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.simulator.fixture.TestInventories;
import io.github.selvitopu.atmsup.supervisor.CapturedLogs;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.snmp4j.CommandResponderEvent;
import org.snmp4j.MessageDispatcher;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.mp.StatusInformation;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.SecurityModel;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;

/** The receiver's own guard: only authPriv USM notifications are processed or acknowledged. */
class TrapReceiverTest {

    private final List<Notification> sink = new ArrayList<>();
    private final TrapReceiver receiver = new TrapReceiver(
            new NotificationMapper(Inventory.from(TestInventories.v01(20000, 20010))), sink::add,
            InstantSource.system());

    @Test
    void unauthenticatedInformIsDroppedWithoutResponse() throws Exception {
        MessageDispatcher dispatcher = mock(MessageDispatcher.class);
        try (CapturedLogs logs = new CapturedLogs(TrapReceiver.class)) {
            receiver.processPdu(event(dispatcher, PDU.INFORM, SecurityModel.SECURITY_MODEL_USM,
                    SecurityLevel.NOAUTH_NOPRIV));
            receiver.processPdu(event(dispatcher, PDU.INFORM, SecurityModel.SECURITY_MODEL_USM,
                    SecurityLevel.AUTH_NOPRIV));
            receiver.processPdu(event(dispatcher, PDU.TRAP, SecurityModel.SECURITY_MODEL_SNMPv2c,
                    SecurityLevel.AUTH_PRIV));
            assertThat(logs.warnings()).singleElement().asString().contains("Unauthenticated notification");
        }
        assertThat(sink).isEmpty();
        verify(dispatcher, never()).returnResponsePdu(anyInt(), anyInt(), any(), anyInt(), any(), anyInt(), any(),
                any());
    }

    @Test
    void authPrivInformIsProcessedAndAcknowledged() throws Exception {
        MessageDispatcher dispatcher = mock(MessageDispatcher.class);
        receiver.processPdu(event(dispatcher, PDU.INFORM, SecurityModel.SECURITY_MODEL_USM,
                SecurityLevel.AUTH_PRIV));
        assertThat(sink).hasSize(1);
        verify(dispatcher).returnResponsePdu(anyInt(), anyInt(), any(), anyInt(), any(), anyInt(), any(),
                any(StatusInformation.class));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CommandResponderEvent<UdpAddress> event(MessageDispatcher dispatcher, int type, int model,
                                                           int level) {
        ScopedPDU pdu = new ScopedPDU();
        pdu.setType(type);
        pdu.setContextEngineID(new OctetString(io.github.selvitopu.atmsup.inventory.EngineIds.of("RAD-N")));
        pdu.add(new org.snmp4j.smi.VariableBinding(SnmpConstants.snmpTrapOID,
                io.github.selvitopu.atmsup.mib.AtmTsOids.NOTIF_EQ_STATUS_CHANGE));
        pdu.add(new org.snmp4j.smi.VariableBinding(
                io.github.selvitopu.atmsup.mib.AtmTsOids.scalar(io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_ID),
                new OctetString("RAD-N")));
        CommandResponderEvent event = mock(CommandResponderEvent.class);
        when(event.getPDU()).thenReturn(pdu);
        when(event.getSecurityModel()).thenReturn(model);
        when(event.getSecurityLevel()).thenReturn(level);
        when(event.getMessageDispatcher()).thenReturn(dispatcher);
        return event;
    }
}
