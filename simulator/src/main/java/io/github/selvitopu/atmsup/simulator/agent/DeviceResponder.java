package io.github.selvitopu.atmsup.simulator.agent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.CommandResponder;
import org.snmp4j.CommandResponderEvent;
import org.snmp4j.MessageException;
import org.snmp4j.PDU;
import org.snmp4j.mp.StatusInformation;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.SecurityModel;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.Null;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.SMIConstants;
import org.snmp4j.smi.VariableBinding;

/**
 * Answers GET, GETNEXT, GETBULK and SET requests of one agent from its current {@link MibView}; only USM authPriv
 * requests are served.
 */
final class DeviceResponder implements CommandResponder {

    /** Upper bound of a GETBULK response PDU: the UDP payload of one 1500-byte Ethernet frame (minus IP and UDP). */
    static final int MAX_BULK_RESPONSE_BYTES = 1472;

    private static final String OPERATOR = "operator";

    private static final Logger log = LoggerFactory.getLogger(DeviceResponder.class);

    private final String deviceId;
    private final Supplier<MibView> view;
    private final SetHandler setHandler;
    private volatile boolean responding = true;

    DeviceResponder(String deviceId, Supplier<MibView> view, SetHandler setHandler) {
        this.deviceId = deviceId;
        this.view = view;
        this.setHandler = setHandler;
    }

    void setResponding(boolean responding) {
        this.responding = responding;
    }

    @Override
    public <A extends Address> void processPdu(CommandResponderEvent<A> event) {
        PDU request = event.getPDU();
        if (request == null || !isRequest(request.getType())) {
            return;
        }
        event.setProcessed(true);
        if (!responding) {
            return;
        }
        PDU response = isUsmAuthPriv(event)
                ? serve(request, event)
                : errorResponse(request, PDU.authorizationError, 0); // no value is served
        try {
            event.getMessageDispatcher().returnResponsePdu(event.getMessageProcessingModel(),
                    event.getSecurityModel(), event.getSecurityName(), event.getSecurityLevel(), response,
                    event.getMaxSizeResponsePDU(), event.getStateReference(), new StatusInformation());
        } catch (MessageException e) {
            log.warn("Device {} cannot send response to {}: {}", deviceId, event.getPeerAddress(), e.getMessage());
        }
    }

    /**
     * A failing view or SET handler answers genErr and is logged here: SNMP4J would swallow the exception silently,
     * and the manager would see a timeout, i.e. a device that looks unreachable.
     */
    private PDU serve(PDU request, CommandResponderEvent<?> event) {
        try {
            return respond(request, event.getMaxSizeResponsePDU(), securityName(event));
        } catch (RuntimeException e) {
            log.error("Device {} failed to answer {} from {}; answering genErr", deviceId,
                    PDU.getTypeString(request.getType()), event.getPeerAddress(), e);
            return errorResponse(request, PDU.genErr, request.size() > 0 ? 1 : 0);
        }
    }

    private static String securityName(CommandResponderEvent<?> event) {
        return new String(event.getSecurityName(), StandardCharsets.UTF_8);
    }

    private static boolean isRequest(int type) {
        return type == PDU.GET || type == PDU.GETNEXT || type == PDU.GETBULK || type == PDU.SET;
    }

    /**
     * A user configured for authPriv still passes the USM at a lower requested level, so the level is enforced here:
     * only USM authPriv requests are served.
     */
    private static boolean isUsmAuthPriv(CommandResponderEvent<?> event) {
        return event.getSecurityModel() == SecurityModel.SECURITY_MODEL_USM
                && event.getSecurityLevel() == SecurityLevel.AUTH_PRIV;
    }

    /** An error response that echoes the request's bindings. */
    private static PDU errorResponse(PDU request, int errorStatus, int errorIndex) {
        PDU response = emptyResponse(request);
        response.setErrorStatus(errorStatus);
        response.setErrorIndex(errorIndex);
        response.addAll(request.getVariableBindings());
        return response;
    }

    private static PDU emptyResponse(PDU request) {
        // a copy keeps the scoped PDU's context engine ID and name; GETBULK's non-repeaters and max-repetitions
        // share the error status and index fields, so those are reset explicitly
        PDU response = (PDU) request.clone();
        response.clear();
        response.setType(PDU.RESPONSE);
        response.setRequestID(request.getRequestID());
        response.setErrorStatus(PDU.noError);
        response.setErrorIndex(0);
        return response;
    }

    private PDU respond(PDU request, int maxSizeResponsePdu, String securityName) {
        PDU response = emptyResponse(request);
        MibView mib = view.get();
        switch (request.getType()) {
            case PDU.GET -> request.getVariableBindings().forEach(vb -> response.add(get(mib, vb.getOid())));
            case PDU.GETNEXT -> request.getVariableBindings().forEach(vb -> response.add(next(mib, vb.getOid())));
            case PDU.GETBULK -> bulk(mib, request, response, Math.min(MAX_BULK_RESPONSE_BYTES, maxSizeResponsePdu));
            case PDU.SET -> set(request, response, securityName);
            default -> throw new IllegalArgumentException("Not a request: " + PDU.getTypeString(request.getType()));
        }
        return response;
    }

    private static VariableBinding get(MibView mib, OID oid) {
        return mib.get(oid).orElseGet(
                () -> new VariableBinding(oid, mib.hasObject(oid) ? Null.noSuchInstance : Null.noSuchObject));
    }

    private static VariableBinding next(MibView mib, OID oid) {
        return mib.next(oid).orElseGet(() -> new VariableBinding(oid, Null.endOfMibView));
    }

    /**
     * RFC 3416 section 4.2.3: the first N bindings are non-repeaters, the other R repeat up to M times. Stops early
     * when a whole repetition is endOfMibView, and drops the bindings that would make the PDU exceed {@code maxBytes}.
     */
    private static void bulk(MibView mib, PDU request, PDU response, int maxBytes) {
        List<? extends VariableBinding> bindings = request.getVariableBindings();
        int nonRepeaters = Math.clamp(request.getNonRepeaters(), 0, bindings.size());
        int maxRepetitions = Math.max(request.getMaxRepetitions(), 0);
        for (int i = 0; i < nonRepeaters; i++) {
            if (!addWithin(response, next(mib, bindings.get(i).getOid()), maxBytes)) {
                return;
            }
        }
        List<OID> cursors = new ArrayList<>();
        for (int i = nonRepeaters; i < bindings.size(); i++) {
            cursors.add(bindings.get(i).getOid());
        }
        for (int repetition = 0; repetition < maxRepetitions && !cursors.isEmpty(); repetition++) {
            boolean allEnded = true;
            for (int r = 0; r < cursors.size(); r++) {
                VariableBinding vb = next(mib, cursors.get(r));
                if (!addWithin(response, vb, maxBytes)) {
                    return;
                }
                cursors.set(r, vb.getOid());
                allEnded &= vb.getVariable().getSyntax() == SMIConstants.EXCEPTION_END_OF_MIB_VIEW;
            }
            if (allEnded) {
                return;
            }
        }
    }

    private static boolean addWithin(PDU response, VariableBinding vb, int maxBytes) {
        response.add(vb);
        if (response.getBERLength() <= maxBytes) {
            return true;
        }
        response.trim();
        return false;
    }

    /**
     * RFC 3416 section 4.2.5: check every binding in order, then apply all or none; the response echoes the request.
     * Per binding: only {@code operator} may write (noAccess), the object must be writable (notWritable), the value an
     * Integer32 (wrongType) within the object's allowed set (wrongValue), and the device model must accept it
     * (inconsistentValue). The first failure decides the error status and index.
     */
    private void set(PDU request, PDU response, String securityName) {
        List<? extends VariableBinding> bindings = request.getVariableBindings();
        setHandler.atomically(() -> {
            for (int i = 0; i < bindings.size(); i++) {
                int errorStatus = checkSet(securityName, bindings.get(i));
                if (errorStatus != PDU.noError) {
                    response.setErrorStatus(errorStatus);
                    response.setErrorIndex(i + 1);
                    return null;
                }
            }
            bindings.forEach(vb -> setHandler.apply(vb.getOid(), vb.getVariable()));
            return null;
        });
        response.addAll(bindings);
    }

    private int checkSet(String securityName, VariableBinding vb) {
        if (!OPERATOR.equals(securityName)) {
            return PDU.noAccess;
        }
        Optional<Set<Integer>> allowed = WritableObjects.allowedValues(vb.getOid());
        if (allowed.isEmpty()) {
            return PDU.notWritable;
        }
        if (!(vb.getVariable() instanceof Integer32 value)) {
            return PDU.wrongType;
        }
        if (!allowed.get().contains(value.getValue())) {
            return PDU.wrongValue;
        }
        SetCheck check = setHandler.check(vb.getOid(), vb.getVariable());
        if (check.errorStatus() != PDU.noError) {
            log.info("Device {} rejects SET {} = {}: {}", deviceId, vb.getOid(), value, check.reason());
            return PDU.inconsistentValue;
        }
        return PDU.noError;
    }
}
