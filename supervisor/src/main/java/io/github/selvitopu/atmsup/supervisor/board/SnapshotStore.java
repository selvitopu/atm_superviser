package io.github.selvitopu.atmsup.supervisor.board;

import java.time.Instant;
import java.util.Optional;

/** Stored copies of the durable board, so it can be rebuilt at any instant from the nearest one; implemented by history. */
public interface SnapshotStore {

    /** Stores the board as it is at {@code takenAt}. */
    void save(Board board, Instant takenAt);

    /** The newest snapshot taken at or before {@code at}, if there is one. */
    Optional<Snapshot> latestAtOrBefore(Instant at);
}
