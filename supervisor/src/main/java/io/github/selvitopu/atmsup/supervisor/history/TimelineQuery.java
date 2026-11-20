package io.github.selvitopu.atmsup.supervisor.history;

import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.AlarmEvent;
import io.github.selvitopu.atmsup.supervisor.board.EventType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The marks of the Replay timeline over a window, read with type filters; the telemetry samples, which are most of
 * the log, are never read. Gaps follow the {@link GapRule} of the board history.
 */
@Component
public class TimelineQuery {

    public static final int MAX_MARKERS = 2000;

    private static final String SAMPLED = EventType.TELEMETRY_SAMPLED.name();
    private static final List<String> FLOOD_TYPES =
            List.of(EventType.ALARM_FLOOD_STARTED.name(), EventType.ALARM_FLOOD_ENDED.name());

    private final EventLogRepository events;
    private final EventCodec codec;
    private final GapRule gapRule;

    public TimelineQuery(EventLogRepository events, EventCodec codec, BoardHistory history) {
        this.events = events;
        this.codec = codec;
        this.gapRule = history.gapRule();
    }

    /** Markers and restarts occurred in [from, to]; a flood or gap that began earlier is included from its start. */
    @Transactional(readOnly = true)
    public Timeline timeline(Instant from, Instant to) {
        List<EventLogEntity> newestFirst = events.findByTypeAndOccurredAtBetweenOrderByOccurredAtDescIdDesc(
                EventType.ALARM_RAISED.name(), from, to, PageRequest.of(0, MAX_MARKERS + 1));
        boolean truncated = newestFirst.size() > MAX_MARKERS;
        List<Timeline.Marker> markers = new ArrayList<>();
        for (EventLogEntity row : newestFirst.subList(0, Math.min(newestFirst.size(), MAX_MARKERS))) {
            Alarm a = ((AlarmEvent) codec.decode(row)).alarm();
            markers.add(new Timeline.Marker(row.getOccurredAt(), a.key().equipmentId(), a.key().code(),
                    a.severity().name(), a.text(), a.suppressed()));
        }
        Collections.reverse(markers);
        List<Instant> restarts = events.findByTypeAndOccurredAtBetweenOrderByOccurredAtAscIdAsc(
                EventType.SUPERVISOR_STARTED.name(), from, to).stream().map(EventLogEntity::getOccurredAt).toList();
        return new Timeline(markers, truncated, floods(from, to), gaps(from, to), restarts);
    }

    private List<Timeline.Span> floods(Instant from, Instant to) {
        List<Timeline.Span> spans = new ArrayList<>();
        Instant open = null;
        Optional<EventLogEntity> before = events.findFirstByTypeInAndOccurredAtLessThanOrderByOccurredAtDescIdDesc(
                FLOOD_TYPES, from);
        if (before.isPresent() && before.get().getType().equals(EventType.ALARM_FLOOD_STARTED.name())) {
            open = before.get().getOccurredAt();
        }
        for (EventLogEntity e : events.findByTypeInAndOccurredAtBetweenOrderByOccurredAtAscIdAsc(FLOOD_TYPES, from,
                to)) {
            if (e.getType().equals(EventType.ALARM_FLOOD_STARTED.name())) {
                open = open == null ? e.getOccurredAt() : open;
            } else if (open != null) {
                spans.add(new Timeline.Span(open, e.getOccurredAt()));
                open = null;
            }
        }
        if (open != null) {
            spans.add(new Timeline.Span(open, null));
        }
        return spans;
    }

    /**
     * After an event data lasts the threshold (none after SUPERVISOR_STOPPING); the stretch from there to the next
     * event, or to the end of the window, is a gap. Before the first event there is no data. Gaps are clipped to start
     * at {@code from}.
     */
    private List<Timeline.Span> gaps(Instant from, Instant to) {
        List<EventLogEntity> window = events.findNonSampleAfterUpTo(SAMPLED, from, to);
        Optional<EventLogEntity> before =
                events.findFirstByTypeNotAndOccurredAtLessThanEqualOrderByOccurredAtDescIdDesc(SAMPLED, from);
        List<Timeline.Span> gaps = new ArrayList<>();
        Instant dataEnds = before.map(this::dataEnds).orElse(from);
        for (EventLogEntity next : window) {
            if (dataEnds.isBefore(next.getOccurredAt())) {
                gaps.add(new Timeline.Span(later(dataEnds, from), next.getOccurredAt()));
            }
            dataEnds = dataEnds(next);
        }
        if (dataEnds.isBefore(to)) {
            gaps.add(new Timeline.Span(later(dataEnds, from), null));
        }
        return gaps;
    }

    private Instant dataEnds(EventLogEntity e) {
        return EventType.SUPERVISOR_STOPPING.name().equals(e.getType()) ? e.getOccurredAt()
                : e.getOccurredAt().plus(gapRule.threshold());
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
