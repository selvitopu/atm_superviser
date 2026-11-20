package io.github.selvitopu.atmsup.supervisor.board;

import java.time.Instant;

/** The durable board as it was at {@code takenAt}; {@code board.lastEventId()} is the last event it includes. */
public record Snapshot(Instant takenAt, Board board) {
}
