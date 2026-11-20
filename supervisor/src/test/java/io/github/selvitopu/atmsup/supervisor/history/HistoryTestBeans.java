package io.github.selvitopu.atmsup.supervisor.history;

import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.simulator.fixture.TestInventories;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** The history beans over the demo inventory, for the JPA slice tests; each test supplies its own clock. */
@TestConfiguration
@Import({EventCodec.class, SnapshotCodec.class, JpaSnapshotStore.class, BoardHistory.class, JpaBoardStore.class})
class HistoryTestBeans {

    static final Inventory INVENTORY = Inventory.from(TestInventories.v01(16101, 10162));

    @Bean
    Inventory inventory() {
        return INVENTORY;
    }
}
