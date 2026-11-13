package io.github.selvitopu.atmsup.simulator.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.selvitopu.atmsup.mib.AtmTsOids;
import org.junit.jupiter.api.Test;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.VariableBinding;

class MibViewTest {

    private static OID b(String suffix) {
        return new OID(AtmTsOids.ROOT.toDottedString() + suffix);
    }

    @Test
    void nextIsStrictlyGreaterAndOrdered() {
        MibView view = MibView.builder()
                .put(b(".1.1.3.0"), new Integer32(1))
                .put(b(".1.1.1.0"), new OctetString("RAD-N"))
                .build();

        assertThat(view.next(b(".1.1"))).map(VariableBinding::getOid).contains(b(".1.1.1.0"));
        assertThat(view.next(b(".1.1.1.0"))).map(VariableBinding::getOid).contains(b(".1.1.3.0"));
        assertThat(view.next(b(".1.1.3.0"))).isEmpty();
    }

    @Test
    void hasObjectDistinguishesInstanceFromObject() {
        MibView view = MibView.builder().put(b(".1.1.3.0"), new Integer32(1)).build();

        assertThat(view.hasObject(b(".1.1.3.1"))).isTrue();
        assertThat(view.hasObject(b(".1.1.99.0"))).isFalse();
    }

    @Test
    void getIsExactAndTheViewIsASnapshotOfTheBuilder() {
        OID key = b(".1.1.3.0");
        MibView.Builder builder = MibView.builder().put(key, new Integer32(1));
        MibView view = builder.build();
        builder.put(b(".1.1.4.0"), new Integer32(1));
        key.append(7);

        assertThat(view.size()).isEqualTo(1);
        assertThat(view.get(b(".1.1.3.0"))).map(VariableBinding::getVariable).contains(new Integer32(1));
        assertThat(view.get(b(".1.1.3"))).isEmpty();
        assertThat(view.get(b(".1.1.4.0"))).isEmpty();
    }
}
