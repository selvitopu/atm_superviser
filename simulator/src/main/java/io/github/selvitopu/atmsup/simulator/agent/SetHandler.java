package io.github.selvitopu.atmsup.simulator.agent;

import org.snmp4j.PDU;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.Variable;

/**
 * Writable objects of a device. A SET request is checked binding by binding first; only if every binding passes are
 * they applied, in request order.
 */
public interface SetHandler {

    /** Rejects every SET with {@code notWritable}. */
    SetHandler READ_ONLY = new SetHandler() {
        @Override
        public SetCheck check(OID oid, Variable value) {
            return new SetCheck(PDU.notWritable, "agent is read-only");
        }

        @Override
        public void apply(OID oid, Variable value) {
            throw new IllegalStateException("agent is read-only");
        }
    };

    SetCheck check(OID oid, Variable value);

    void apply(OID oid, Variable value);
}
