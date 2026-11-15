package io.github.selvitopu.atmsup.simulator.agent;

import java.util.function.Supplier;
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

    /**
     * Runs {@code body}, which calls {@link #check} and {@link #apply}, as one step that no other change to the device
     * model can interleave with. The default just runs it.
     */
    default <T> T atomically(Supplier<T> body) {
        return body.get();
    }
}
