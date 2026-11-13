package io.github.selvitopu.atmsup.simulator.agent;

import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;

/**
 * An immutable, lexicographically ordered snapshot of the object instances one agent exposes. Device models build a
 * fresh view whenever their state changes; the agent answers every request from one view.
 */
public final class MibView {

    private final NavigableMap<OID, Variable> entries;

    private MibView(TreeMap<OID, Variable> entries) {
        this.entries = Collections.unmodifiableNavigableMap(new TreeMap<>(entries));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The instance {@code oid} itself, if present. */
    public Optional<VariableBinding> get(OID oid) {
        Variable value = entries.get(oid);
        return value == null ? Optional.empty() : Optional.of(new VariableBinding(oid, value));
    }

    /** The first instance strictly greater than {@code oid} (GETNEXT order), if any. */
    public Optional<VariableBinding> next(OID oid) {
        Map.Entry<OID, Variable> entry = entries.higherEntry(oid);
        return entry == null ? Optional.empty() : Optional.of(new VariableBinding(entry.getKey(), entry.getValue()));
    }

    /**
     * Whether the object that {@code oid} would be an instance of exists, i.e. some instance starts with the parent of
     * {@code oid}. Used to tell noSuchInstance (true) from noSuchObject (false) when {@code oid} itself is missing.
     */
    public boolean hasObject(OID oid) {
        if (oid.size() == 0) {
            return false;
        }
        OID parent = new OID(oid.getValue(), 0, oid.size() - 1);
        OID candidate = entries.ceilingKey(parent);
        return candidate != null && candidate.startsWith(parent);
    }

    public int size() {
        return entries.size();
    }

    public static final class Builder {

        private final TreeMap<OID, Variable> entries = new TreeMap<>();

        private Builder() {
        }

        /** Adds or replaces an instance; the OID is copied, so callers may reuse theirs. */
        public Builder put(OID oid, Variable value) {
            entries.put(new OID(oid), value);
            return this;
        }

        public MibView build() {
            return new MibView(entries);
        }
    }
}
