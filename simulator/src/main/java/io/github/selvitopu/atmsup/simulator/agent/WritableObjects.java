package io.github.selvitopu.atmsup.simulator.agent;

import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_COMMAND;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_MAINTENANCE_MODE;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.scalar;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.snmp4j.smi.OID;

/** The only two writable objects of a device and the Integer32 values each accepts. */
final class WritableObjects {

    /** TruthValue: 1 = true, 2 = false. */
    private static final Set<Integer> TRUTH_VALUE = Set.of(1, 2);

    /** The command object accepts becomeActive(2), restart(3) and resetCounters(4); none(1) is not settable. */
    private static final Set<Integer> COMMANDS = Set.of(2, 3, 4);

    private static final Map<OID, Set<Integer>> ALLOWED = Map.of(
            scalar(EQ_MAINTENANCE_MODE), TRUTH_VALUE,
            scalar(EQ_COMMAND), COMMANDS);

    private WritableObjects() {
    }

    /** The accepted values of the object instance, or empty if it is not writable. */
    static Optional<Set<Integer>> allowedValues(OID instanceOid) {
        return Optional.ofNullable(ALLOWED.get(instanceOid));
    }
}
