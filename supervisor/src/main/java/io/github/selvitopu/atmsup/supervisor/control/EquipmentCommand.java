package io.github.selvitopu.atmsup.supervisor.control;

import io.github.selvitopu.atmsup.mib.AtmTsOids;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.VariableBinding;

/**
 * The commands an operator may send to a unit, each one SET of a writable object in DEMO-ATM-TECHSUP-MIB. Whether a
 * command fits the unit's state (restart only in maintenance, make active only for a standby unit) is the agent's
 * decision: it answers inconsistentValue otherwise.
 */
public enum EquipmentCommand {
    MAINTENANCE_ON(AtmTsOids.EQ_MAINTENANCE_MODE, 1),  // TruthValue true(1)
    MAINTENANCE_OFF(AtmTsOids.EQ_MAINTENANCE_MODE, 2), // false(2)
    RESTART(AtmTsOids.EQ_COMMAND, 3),                  // restart(3)
    MAKE_ACTIVE(AtmTsOids.EQ_COMMAND, 2);              // becomeActive(2)

    private final OID object;
    private final int value;

    EquipmentCommand(OID object, int value) {
        this.object = object;
        this.value = value;
    }

    VariableBinding binding() {
        return new VariableBinding(AtmTsOids.scalar(object), new Integer32(value));
    }
}
