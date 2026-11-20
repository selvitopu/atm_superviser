package io.github.selvitopu.atmsup.supervisor.history;

import io.github.selvitopu.atmsup.supervisor.board.Board;
import io.github.selvitopu.atmsup.supervisor.board.Snapshot;
import io.github.selvitopu.atmsup.supervisor.board.SnapshotStore;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The board snapshots in PostgreSQL. */
@Component
public class JpaSnapshotStore implements SnapshotStore {

    private final BoardSnapshotRepository snapshots;
    private final SnapshotCodec codec;

    public JpaSnapshotStore(BoardSnapshotRepository snapshots, SnapshotCodec codec) {
        this.snapshots = snapshots;
        this.codec = codec;
    }

    @Override
    @Transactional
    public void save(Board board, Instant takenAt) {
        snapshots.save(new BoardSnapshotEntity(takenAt, board.lastEventId(), SnapshotCodec.SCHEMA_VERSION,
                codec.encode(board)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Snapshot> latestAtOrBefore(Instant at) {
        return snapshots.findFirstByTakenAtLessThanEqualOrderByTakenAtDescIdDesc(at)
                .map(e -> new Snapshot(e.getTakenAt(), codec.decode(e.getBoard(), e.getLastEventId())));
    }
}
