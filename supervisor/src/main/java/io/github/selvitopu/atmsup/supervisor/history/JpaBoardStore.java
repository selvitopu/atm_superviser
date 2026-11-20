package io.github.selvitopu.atmsup.supervisor.history;

import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.board.Board;
import io.github.selvitopu.atmsup.supervisor.board.BoardStore;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.AlarmEvent;
import io.github.selvitopu.atmsup.supervisor.board.StoredEvent;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The board's PostgreSQL store: the event log plus the current state of every alarm, written in one transaction. */
@Component
public class JpaBoardStore implements BoardStore {

    private final EventLogRepository events;
    private final AlarmRepository alarms;
    private final EventCodec codec;
    private final InstantSource clock;
    private final BoardHistory history;

    public JpaBoardStore(EventLogRepository events, AlarmRepository alarms, EventCodec codec, InstantSource clock,
                         BoardHistory history) {
        this.events = events;
        this.alarms = alarms;
        this.codec = codec;
        this.clock = clock;
        this.history = history;
    }

    @Override
    @Transactional
    public List<StoredEvent> commit(List<DomainEvent> batch) {
        Instant recordedAt = clock.instant();
        List<StoredEvent> stored = new ArrayList<>(batch.size());
        for (DomainEvent e : batch) {
            EventLogEntity row = events.save(codec.encode(e, recordedAt));
            if (e instanceof AlarmEvent a) {
                upsert(a.alarm());
            }
            stored.add(new StoredEvent(row.getId(), e));
        }
        return stored;
    }

    /**
     * Flushed at once, so the unique open key sees the alarm changes in event order: an alarm that is CLOSED frees
     * its key before a later event of the same commit raises a new alarm with that key.
     */
    private void upsert(Alarm a) {
        alarms.findById(a.id()).ifPresentOrElse(entity -> entity.update(a), () -> alarms.save(new AlarmEntity(a)));
        alarms.flush();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasEvents() {
        return events.existsByIdIsNotNull();
    }

    /** {@link BoardHistory#fold} for now. */
    @Override
    public Board foldedBoard() {
        return history.fold(clock.instant());
    }
}
