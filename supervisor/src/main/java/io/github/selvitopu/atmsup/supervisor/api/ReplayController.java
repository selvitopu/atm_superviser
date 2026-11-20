package io.github.selvitopu.atmsup.supervisor.api;

import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.MessageDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.RaisedDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.ReplayBoardDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.ReplayEventDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.ReplayEventsDto;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.AlarmEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.CommandEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.EquipmentEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.TelemetrySampleEvent;
import io.github.selvitopu.atmsup.supervisor.board.EventType;
import io.github.selvitopu.atmsup.supervisor.board.StoredEvent;
import io.github.selvitopu.atmsup.supervisor.history.BoardHistory;
import io.github.selvitopu.atmsup.supervisor.history.EventCodec;
import io.github.selvitopu.atmsup.supervisor.history.EventLogEntity;
import io.github.selvitopu.atmsup.supervisor.history.EventLogRepository;
import io.github.selvitopu.atmsup.supervisor.history.Timeline;
import io.github.selvitopu.atmsup.supervisor.history.TimelineQuery;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The stored past for the Replay screen: the board at an instant, the events after an id, the timeline marks of a
 * window and the stored raise of an alarm. Read-only; any signed-in role may ask.
 */
@RestController
@RequestMapping("/api/v1/history")
public class ReplayController {

    static final int DEFAULT_LIMIT = 500;
    static final int MAX_LIMIT = 1000;
    static final Duration MAX_TIMELINE_WINDOW = Duration.ofDays(7);

    private final BoardHistory history;
    private final EventLogRepository events;
    private final EventCodec codec;
    private final TimelineQuery timeline;
    private final DtoMapper mapper;
    private final StreamMessages messages;
    private final Inventory inventory;

    public ReplayController(BoardHistory history, EventLogRepository events, EventCodec codec,
                            TimelineQuery timeline, DtoMapper mapper, StreamMessages messages, Inventory inventory) {
        this.history = history;
        this.events = events;
        this.codec = codec;
        this.timeline = timeline;
        this.mapper = mapper;
        this.messages = messages;
        this.inventory = inventory;
    }

    @GetMapping("/board")
    public ReplayBoardDto board(@RequestParam("at") Instant at) {
        return mapper.replayBoard(history.boardAt(at), inventory.centre(),
                history.gapRule().threshold().toSeconds());
    }

    /** Events with an id above {@code after} (default 0) that occurred by {@code until}, in id order. */
    @GetMapping("/events")
    public ReplayEventsDto events(@RequestParam(name = "after", defaultValue = "0") long after,
                                  @RequestParam(name = "until", required = false) Instant until,
                                  @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw badRequest("The limit must be from 1 to " + MAX_LIMIT);
        }
        PageRequest page = PageRequest.of(0, limit + 1); // one more than asked, to know whether more remain
        List<EventLogEntity> rows = until == null ? events.findPageAfter(after, page)
                : events.findPageAfterUntil(after, until, page);
        boolean more = rows.size() > limit;
        List<ReplayEventDto> out = new ArrayList<>();
        for (EventLogEntity row : rows.subList(0, Math.min(rows.size(), limit))) {
            out.add(dto(new StoredEvent(row.getId(), codec.decode(row))));
        }
        return new ReplayEventsDto(out, more);
    }

    @GetMapping("/timeline")
    public Timeline timeline(@RequestParam("from") Instant from, @RequestParam("to") Instant to) {
        if (!from.isBefore(to)) {
            throw badRequest("The timeline window must start before it ends");
        }
        if (Duration.between(from, to).compareTo(MAX_TIMELINE_WINDOW) > 0) {
            throw badRequest("The timeline window is at most 7 days");
        }
        return timeline.timeline(from, to);
    }

    /** When the supervisor stored the newest raise of the alarm; a resync raise carries an earlier device time. */
    @GetMapping("/alarms/{id}/raised")
    public RaisedDto raised(@PathVariable String id) {
        return events.findFirstByTypeAndEntityIdOrderByIdDesc(EventType.ALARM_RAISED.name(), id)
                .map(row -> new RaisedDto(row.getOccurredAt()))
                .orElseThrow(() -> new NoSuchElementException("No stored raise of alarm " + id));
    }

    private ReplayEventDto dto(StoredEvent stored) {
        DomainEvent e = stored.event();
        SseMessage m = messages.replay(stored);
        return new ReplayEventDto(stored.id(), e.occurredAt(), e.type().name(), equipmentId(e), actor(e),
                EventSummaries.describe(stored), new MessageDto(m.name(), m.data()));
    }

    private static String equipmentId(DomainEvent e) {
        return switch (e) {
            case EquipmentEvent ev -> ev.state().id();
            case AlarmEvent ev -> ev.alarm().key().equipmentId();
            case TelemetrySampleEvent ev -> ev.equipmentId();
            case CommandEvent ev -> ev.equipmentId();
            default -> null;
        };
    }

    private static String actor(DomainEvent e) {
        return switch (e) {
            case AlarmEvent ev -> ev.actor();
            case CommandEvent ev -> ev.actor();
            default -> null;
        };
    }

    private static ErrorResponseException badRequest(String detail) {
        return new ErrorResponseException(HttpStatus.BAD_REQUEST,
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail), null);
    }
}
