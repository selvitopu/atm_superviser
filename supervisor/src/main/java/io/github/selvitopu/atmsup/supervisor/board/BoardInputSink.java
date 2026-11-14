package io.github.selvitopu.atmsup.supervisor.board;

import java.util.concurrent.CompletableFuture;

/** Accepts board inputs from any thread; they are processed one at a time, in submission order. */
public interface BoardInputSink {

    /**
     * The future completes on the board writer thread once the input is processed, or at once with a failed result
     * if the writer is not running. It never completes exceptionally.
     */
    CompletableFuture<BoardResult> submit(BoardInput in);
}
