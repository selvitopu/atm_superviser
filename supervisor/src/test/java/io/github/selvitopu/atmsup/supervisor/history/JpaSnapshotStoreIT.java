package io.github.selvitopu.atmsup.supervisor.history;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.selvitopu.atmsup.supervisor.board.Board;
import io.github.selvitopu.atmsup.supervisor.board.Snapshot;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({SnapshotCodec.class, JpaSnapshotStore.class})
class JpaSnapshotStoreIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    private static final Instant t1 = Instant.parse("2026-01-01T10:00:00.123Z");
    private static final Instant t2 = t1.plusSeconds(300);
    private static final Instant t3 = t2.plusSeconds(300);

    @Autowired
    private JpaSnapshotStore store;
    @Autowired
    private JdbcTemplate jdbc;

    private static Board board(long lastEventId) {
        return new Board(Map.of(), Map.of(), Map.of(), lastEventId);
    }

    @Test
    void latestAtOrBeforePicksTheNewestNotAfter() {
        store.save(board(10), t1);
        store.save(board(20), t2);
        store.save(board(30), t3);

        assertThat(store.latestAtOrBefore(t2.plusSeconds(1))).get()
                .isEqualTo(new Snapshot(t2, board(20)));
        assertThat(store.latestAtOrBefore(t2)).get().extracting(Snapshot::takenAt).isEqualTo(t2);
        assertThat(store.latestAtOrBefore(t1.minusSeconds(1))).isEmpty();
    }

    @Test
    void theChangesetApplies() {
        store.save(board(7), t1);

        assertThat(jdbc.queryForMap("select last_event_id, schema_version, jsonb_typeof(board) as kind "
                + "from board_snapshot")).containsEntry("last_event_id", 7L).containsEntry("schema_version", 1)
                .containsEntry("kind", "object");
        assertThat(jdbc.queryForObject("select count(*) from pg_indexes where indexname = 'ix_board_snapshot_taken'",
                Integer.class)).isEqualTo(1);
    }
}
