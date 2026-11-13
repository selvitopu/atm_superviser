package io.github.selvitopu.atmsup.supervisor.snmp;

import io.github.selvitopu.atmsup.inventory.Device;
import java.util.List;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.VariableBinding;

/** Synchronous SNMP reads of one device. */
public interface SnmpClient {

    /** GET of the given instance OIDs; the bindings come back in the response order (they may hold exception values). */
    List<VariableBinding> get(Device d, List<OID> oids) throws SnmpRequestException;

    /** All bindings of {@code subtree}, read with GETBULK (non-repeaters 0, max-repetitions 20). */
    List<VariableBinding> walk(Device d, OID subtree) throws SnmpRequestException;
}
