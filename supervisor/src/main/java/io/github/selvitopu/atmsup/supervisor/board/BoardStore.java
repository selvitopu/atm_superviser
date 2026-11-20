package io.github.selvitopu.atmsup.supervisor.board;

import java.util.List;

/** Durable storage of the event log and the alarm table, implemented by the history module. */
public interface BoardStore {

    /**
     * Stores the events in one transaction, in order.
     *
     * @return the events with their log ids, in the same order
     */
    List<StoredEvent> commit(List<DomainEvent> events);

    /** False only before the very first commit. */
    boolean hasEvents();

    /**
     * The durable board now, rebuilt from the stored log: the newest snapshot plus a fold of every later event, or a
     * fold of the whole log when there is no snapshot. The writer starts from it, so the live board stays the fold of
     * the log across restarts (ADR-0004).
     */
    Board foldedBoard();
}
