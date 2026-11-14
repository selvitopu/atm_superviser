package io.github.selvitopu.atmsup.supervisor.board;

import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import java.util.List;

/** Durable storage of the event log and the alarm table, implemented by the history module. */
public interface BoardStore {

    /**
     * Stores the events in one transaction, in order.
     *
     * @return the events with their log ids, in the same order
     */
    List<StoredEvent> commit(List<DomainEvent> events);

    /** Every alarm that is not CLOSED. */
    List<Alarm> loadOpenAlarms();

    /** False only before the very first commit. */
    boolean hasEvents();
}
