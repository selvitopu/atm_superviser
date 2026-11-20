package io.github.selvitopu.atmsup.supervisor.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One stored copy of the durable board; {@code board} is the JSON written by {@link SnapshotCodec}. */
@Entity
@Table(name = "board_snapshot")
public class BoardSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "taken_at", nullable = false)
    private Instant takenAt;

    @Column(name = "last_event_id", nullable = false)
    private long lastEventId;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "board", nullable = false, columnDefinition = "jsonb")
    private String board;

    protected BoardSnapshotEntity() {
    }

    public BoardSnapshotEntity(Instant takenAt, long lastEventId, int schemaVersion, String board) {
        this.takenAt = takenAt;
        this.lastEventId = lastEventId;
        this.schemaVersion = schemaVersion;
        this.board = board;
    }

    public Instant getTakenAt() {
        return takenAt;
    }

    public long getLastEventId() {
        return lastEventId;
    }

    public String getBoard() {
        return board;
    }
}
