package io.github.selvitopu.atmsup.supervisor.snmp;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.simulator.fixture.TestInventories;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.snmp4j.Snmp;
import org.snmp4j.transport.DefaultUdpTransportMapping;

class SnmpSessionTest {

    /** The lifecycle stop and Spring's inferred destroy method both close the session; only the first one acts. */
    @Test
    void closeIsIdempotent() throws Exception {
        Inventory inv = Inventory.from(TestInventories.v01(16101, 10162));
        try (MockedConstruction<DefaultUdpTransportMapping> transports =
                     mockConstruction(DefaultUdpTransportMapping.class);
             MockedConstruction<Snmp> engines = mockConstruction(Snmp.class)) {
            SnmpSession session = new SnmpSession(inv, TestInventories.demoCredentials(), 1, TestInventories.HOST, 0);

            session.close();

            assertThatCode(session::close).doesNotThrowAnyException();
            verify(engines.constructed().getFirst(), times(1)).close();
        }
    }
}
