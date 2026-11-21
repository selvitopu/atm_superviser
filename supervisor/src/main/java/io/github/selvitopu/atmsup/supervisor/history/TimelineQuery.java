package io.github.selvitopu.atmsup.supervisor.history;

import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.AlarmEvent;
import io.github.selvitopu.atmsup.supervisor.board.EventType;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
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

    private static final String FLOOD_STARTED = EventType.ALARM_FLOOD_STARTED.name();
    /** A restart clears a flood without storing a flood end, so it closes a span too. */
    private static final List<String> FLOOD_TYPES = List.of(FLOOD_STARTED, EventType.ALARM_FLOOD_ENDED.name(),
            EventType.SUPERVISOR_STARTED.name());
    /** Every type but the samples, which are most of the log: named, so that the type index serves the query. */
    private static final List<String> RECORDING_TYPES = Arrays.stream(EventType.values())
            .filter(t -> t != EventType.TELEMETRY_SAMPLED).map(EventType::name).toList();

    private final EventLogRepository events;
    private final EventCodec codec;
    private final GapRule gapRule;
    private final InstantSource clock;

    public TimelineQuery(EventLogRepository events, EventCodec codec, BoardHistory history,
                         InstantSource clock) {
        this.events = events;
        this.codec = codec;
        this.gapRule = history.gapRule();
        this.clock = clock;
    }

    /**
     * Markers and restarts occurred in [from, to]. A flood or gap that began before {@code from} is reported with its
     * true start; gaps are judged up to the earlier of {@code to} and now, as the board history never looks past now.
     */
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
        if (before.isPresent() && before.get().getType().equals(FLOOD_STARTED)) {
            open = before.get().getOccurredAt();
        }
        for (EventLogEntity e : events.findByTypeInAndOccurredAtBetweenOrderByOccurredAtAscIdAsc(FLOOD_TYPES, from,
                to)) {
            if (e.getType().equals(FLOOD_STARTED)) {
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
     * event, or to the end of the judged window, is a gap. Before the first event there is no data, and the gap
     * starts at {@code from}. Only the time and type of the events are read.
     */
    private List<Timeline.Span> gaps(Instant from, Instant to) {
        Instant end = to.isAfter(clock.instant()) ? clock.instant() : to;
        if (!from.isBefore(end)) {
            return List.of();
        }
        Optional<EventStamp> before = events.findNewestStampAtOrBefore(RECORDING_TYPES, from, PageRequest.of(0, 1))
                .stream().findFirst();
        List<Timeline.Span> gaps = new ArrayList<>();
        Instant dataEnds = before.map(this::dataEnds).orElse(from);
        for (EventStamp next : events.findStampsAfterUpTo(RECORDING_TYPES, from, end)) {
            if (dataEnds.isBefore(next.occurredAt())) {
                gaps.add(new Timeline.Span(dataEnds, next.occurredAt()));
            }
            dataEnds = dataEnds(next);
        }
        if (dataEnds.isBefore(end)) {
            gaps.add(new Timeline.Span(dataEnds, null));
        }
        return gaps;
    }

    private Instant dataEnds(EventStamp e) {
        return EventType.SUPERVISOR_STOPPING.name().equals(e.type()) ? e.occurredAt()
                : e.occurredAt().plus(gapRule.threshold());
    }
}
