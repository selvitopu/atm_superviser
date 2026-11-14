package io.github.selvitopu.atmsup.supervisor.board;

import java.time.Instant;
import java.util.List;

/**
 * Spring application events published by the board writer, synchronously on its thread and only after a successful
 * commit. Listeners must not block (amendment §4): they only enqueue, and never join a
 * {@link BoardInputSink#submit} future, which would deadlock the writer.
 */
public final class BoardEvents {

    private BoardEvents() {
    }

    /** The events of one commit, with their log ids, and the live board after applying them. */
    public record BoardCommitted(List<StoredEvent> events, LiveBoard board) {
    }

    /** New live telemetry for one device; never stored. */
    public record TelemetryUpdated(String equipmentId, Telemetry telemetry, Instant serverTime) {
    }

    /** A trap asks for a prompt full poll of the device. */
    public record RepollRequested(String equipmentId) {
    }

    /** The board has been initialised and the start-up events committed; polling may begin. */
    public record BoardReady() {
    }
}
