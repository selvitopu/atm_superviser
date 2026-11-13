package io.github.selvitopu.atmsup.supervisor.snmp;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.supervisor.snmp.SnmpRequestException.Kind;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.UserTarget;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;

/** {@link SnmpClient} over the supervisor's {@link SnmpSession}. */
public class Snmp4jClient implements SnmpClient {

    private static final int MAX_REPETITIONS = 20;
    /** A walk that needs more rounds than this is not converging; it is abandoned. */
    private static final int MAX_ROUNDS = 500;

    /** Report counters worth naming; any other report is shown by its OID. */
    private static final Map<OID, String> REPORTS = Map.of(
            SnmpConstants.usmStatsUnsupportedSecLevels, "usmStatsUnsupportedSecLevels",
            SnmpConstants.usmStatsNotInTimeWindows, "usmStatsNotInTimeWindows",
            SnmpConstants.usmStatsUnknownUserNames, "usmStatsUnknownUserNames",
            SnmpConstants.usmStatsUnknownEngineIDs, "usmStatsUnknownEngineIDs",
            SnmpConstants.usmStatsWrongDigests, "usmStatsWrongDigests",
            SnmpConstants.usmStatsDecryptionErrors, "usmStatsDecryptionErrors");

    private final SnmpSession session;

    public Snmp4jClient(SnmpSession session) {
        this.session = session;
    }

    @Override
    public List<VariableBinding> get(Device d, List<OID> oids) throws SnmpRequestException {
        ScopedPDU pdu = new ScopedPDU();
        pdu.setType(PDU.GET);
        oids.forEach(oid -> pdu.add(new VariableBinding(oid)));
        return List.copyOf(send(d, pdu).getAll());
    }

    @Override
    public List<VariableBinding> walk(Device d, OID subtree) throws SnmpRequestException {
        List<VariableBinding> collected = new ArrayList<>();
        OID last = subtree;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            ScopedPDU pdu = new ScopedPDU();
            pdu.setType(PDU.GETBULK);
            pdu.setNonRepeaters(0);
            pdu.setMaxRepetitions(MAX_REPETITIONS);
            pdu.add(new VariableBinding(last));
            List<VariableBinding> rows = send(d, pdu).getAll();
            if (rows.isEmpty()) {
                return List.copyOf(collected);
            }
            for (VariableBinding vb : rows) {
                Variable v = vb.getVariable();
                if (v.isException() || !vb.getOid().startsWith(subtree)) {
                    return List.copyOf(collected); // endOfMibView, or left the subtree
                }
                if (vb.getOid().compareTo(last) <= 0) {
                    throw new SnmpRequestException(Kind.MALFORMED, "walk of " + subtree + " is not increasing at "
                            + vb.getOid());
                }
                collected.add(vb);
                last = vb.getOid();
            }
        }
        throw new SnmpRequestException(Kind.MALFORMED, "walk of " + subtree + " did not end");
    }

    private PDU send(Device d, PDU pdu) {
        UserTarget<UdpAddress> target = session.target(d);
        String where = d.host() + ":" + d.port();
        if (!target.getAddress().isValid()) {
            throw new SnmpRequestException(Kind.TIMEOUT, "cannot resolve host " + d.host());
        }
        ResponseEvent<UdpAddress> event;
        try {
            event = session.snmp().send(pdu, target);
        } catch (IOException e) {
            throw new SnmpRequestException(Kind.TIMEOUT, "send to " + where + " failed: " + e.getMessage());
        }
        PDU response = event == null ? null : event.getResponse();
        if (response == null) {
            throw new SnmpRequestException(Kind.TIMEOUT, "no response from " + where);
        }
        if (response.getType() == PDU.REPORT) {
            String name = response.size() == 0 ? "empty report" : reportName(response.get(0).getOid());
            throw new SnmpRequestException(Kind.AUTH_FAILURE, name);
        }
        if (response.getErrorStatus() != PDU.noError) {
            throw new SnmpRequestException(Kind.ERROR_STATUS,
                    response.getErrorStatusText() + " at index " + response.getErrorIndex());
        }
        return response;
    }

    private static String reportName(OID oid) {
        return REPORTS.getOrDefault(oid, oid.toString());
    }
}
