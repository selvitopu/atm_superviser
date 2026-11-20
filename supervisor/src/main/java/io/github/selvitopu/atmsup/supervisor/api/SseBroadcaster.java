package io.github.selvitopu.atmsup.supervisor.api;

import io.github.selvitopu.atmsup.supervisor.api.Dtos.FloodDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.HeartbeatDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.SupervisorEventDto;
import io.github.selvitopu.atmsup.supervisor.board.BoardEvents.BoardCommitted;
import io.github.selvitopu.atmsup.supervisor.board.BoardEvents.TelemetryUpdated;
import io.github.selvitopu.atmsup.supervisor.board.BoardWriter;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.AlarmEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.AlarmFloodEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.CommandEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.EquipmentEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.ServiceEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.SupervisorEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.TelemetrySampleEvent;
import io.github.selvitopu.atmsup.supervisor.board.EventType;
import io.github.selvitopu.atmsup.supervisor.board.LiveBoard;
import io.github.selvitopu.atmsup.supervisor.board.StoredEvent;
import io.github.selvitopu.atmsup.supervisor.board.Telemetry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.security.web.session.HttpSessionIdChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Streams the board to SSE subscribers without ever blocking the board writer (amendment §4). The listeners and the
 * heartbeat only enqueue into each client's own bounded queue and return at once, whatever the clients are doing.
 * Durable messages keep their order and carry {@code id = event_log.id}; a client whose queue is full is
 * disconnected rather than left to miss one, and its browser resumes from the ring of the last {@code ringSize}
 * durable messages, or is told to {@code resync} when the ring no longer covers its last event id.
 */
@Component
public class SseBroadcaster {

    static final Duration EMITTER_TIMEOUT = Duration.ofMinutes(30);
    static final String HEARTBEAT = "heartbeat";
    private static final SseMessage RESYNC = new SseMessage("resync", null, Map.of());
    private static final Logger log = LoggerFactory.getLogger(SseBroadcaster.class);

    private final DtoMapper mapper;
    private final BoardWriter writer;
    private final InstantSource clock;
    private final MeterRegistry meters;
    private final int ringSize;
    private final int clientQueueSize;
    private final Supplier<SseEmitter> emitters;
    private final Set<SseClient> clients = ConcurrentHashMap.newKeySet();
    private final Counter resyncs;

    /** Orders ring appends against subscriptions, so a resuming client gets no gap and no duplicate. */
    private final Object lock = new Object();
    /** The last {@code ringSize} durable messages, oldest first. Guarded by {@link #lock}. */
    private final ArrayDeque<SseMessage> ring;
    /**
     * Every broadcast event with an id in ({@code floor}, {@code newest}] is in the ring; ids in between that are not
     * there were stored only (TELEMETRY_SAMPLED). {@code newest} is the newest stored id, so a client resuming from
     * {@code /board}'s lastEventId is never "ahead". Guarded by {@link #lock}; meaningful once {@code seen}.
     */
    private long floor;
    private long newest;
    private boolean seen;
    private boolean closed;

    @Autowired
    public SseBroadcaster(DtoMapper mapper, BoardWriter writer, InstantSource clock, MeterRegistry meters,
                          @Value("${atm.supervisor.sse.ring-size:1000}") int ringSize,
                          @Value("${atm.supervisor.sse.client-queue-size:256}") int clientQueueSize) {
        this(mapper, writer, clock, meters, ringSize, clientQueueSize,
                () -> new SseEmitter(EMITTER_TIMEOUT.toMillis()));
    }

    /** Tests pass emitters that record, block or fail instead of writing to a response. */
    SseBroadcaster(DtoMapper mapper, BoardWriter writer, InstantSource clock, MeterRegistry meters, int ringSize,
                   int clientQueueSize, Supplier<SseEmitter> emitters) {
        this.mapper = mapper;
        this.writer = writer;
        this.clock = clock;
        this.meters = meters;
        this.ringSize = ringSize;
        this.clientQueueSize = clientQueueSize;
        this.emitters = emitters;
        this.ring = new ArrayDeque<>(ringSize);
        Gauge.builder("atmsup.sse.clients", clients, Set::size)
                .description("Connected SSE clients").register(meters);
        Gauge.builder("atmsup.sse.queue.fill.max", this, SseBroadcaster::maxFill)
                .description("Highest share of a client's durable queue in use, from 0 to 1").register(meters);
        resyncs = Counter.builder("atmsup.sse.resyncs")
                .description("Subscribers told to resync because the ring did not cover their last event id")
                .register(meters);
        List.of(SseClient.OVERFLOW, SseClient.IO, SseClient.TIMEOUT, SseClient.ERROR, SseClient.SESSION_ENDED)
                .forEach(this::disconnects);
    }

    /**
     * A new client. Its last event id is the {@code Last-Event-ID} header (a reconnecting EventSource) or else the
     * parameter (a first connection resuming from {@code /board}); anything that is not a number counts as absent.
     */
    public SseEmitter subscribe(String lastEventIdHeader, String lastEventIdParam, String sessionId) {
        Long lastEventId = parse(StringUtils.hasText(lastEventIdHeader) ? lastEventIdHeader : lastEventIdParam);
        SseEmitter emitter = emitters.get();
        SseClient client = new SseClient(emitter, clientQueueSize, sessionId, this::onClientClosed);
        synchronized (lock) {
            if (closed) {
                client.disconnect(SseClient.SHUTDOWN);
                return emitter;
            }
            if (lastEventId != null) {
                List<SseMessage> replay = replayAfter(lastEventId);
                if (replay == null || replay.size() > clientQueueSize) {
                    client.offerDurable(RESYNC); // first in its queue: before any live event
                    resyncs.increment();
                } else {
                    replay.forEach(client::offerDurable); // fits: the queue is new
                }
            }
            clients.add(client);
        }
        if (client.closeReason() != null) {
            clients.remove(client); // closed before it was added
        }
        return emitter;
    }

    /**
     * On the board writer's thread: appends each broadcast event to the ring and queues it for every client. A commit
     * whose messages cannot be built is never dropped silently: see {@link #gap}.
     */
    @EventListener
    public void onCommitted(BoardCommitted committed) {
        List<StoredEvent> events = committed.events();
        if (events.isEmpty()) {
            return;
        }
        LiveBoard live = writer.current();
        List<SseMessage> messages = new ArrayList<>(events.size());
        try {
            for (StoredEvent e : events) {
                SseMessage m = message(e, live);
                if (m != null) {
                    messages.add(m);
                }
            }
        } catch (RuntimeException e) {
            log.error("Cannot map the events {}..{} for the stream; disconnecting every client so that it resyncs",
                    events.getFirst().id(), events.getLast().id(), e);
            gap(events.getLast().id());
            return;
        }
        synchronized (lock) {
            if (!seen) {
                floor = events.getFirst().id() - 1;
                seen = true;
            }
            for (SseMessage m : messages) {
                append(m);
                for (SseClient c : clients) {
                    if (!c.offerDurable(m)) {
                        c.disconnect(SseClient.OVERFLOW); // at once: no later event may reach it before this one
                    }
                }
            }
            newest = Math.max(newest, events.getLast().id());
        }
    }

    /**
     * A commit up to {@code lastId} could not be broadcast. The ring is emptied and both {@code floor} and
     * {@code newest} move to {@code lastId}, so no resume can replay across the gap (a client that was behind it gets
     * {@code resync}), and every client is disconnected with reason {@code error}, so it reconnects and catches up.
     */
    private void gap(long lastId) {
        synchronized (lock) {
            ring.clear();
            floor = Math.max(floor, lastId);
            newest = Math.max(newest, lastId);
            seen = true;
            clients.forEach(c -> c.disconnect(SseClient.ERROR));
        }
    }

    @EventListener
    public void onTelemetry(TelemetryUpdated updated) {
        SseMessage m = new SseMessage("telemetry", null,
                mapper.telemetry(updated.equipmentId(), updated.telemetry(), updated.serverTime()));
        clients.forEach(c -> c.putVolatile(updated.equipmentId(), m));
    }

    @Scheduled(fixedRate = 15000)
    public void heartbeat() {
        SseMessage m = new SseMessage(HEARTBEAT, null, new HeartbeatDto(clock.instant()));
        clients.forEach(c -> c.putVolatile(HEARTBEAT, m));
    }

    /**
     * A session that ends (sign-out, expiry, an administrator's change) takes its open streams with it at once; the
     * browser's reconnect then meets 401 and goes to the sign-in screen.
     */
    @EventListener
    public void onSessionDestroyed(HttpSessionDestroyedEvent event) {
        String id = event.getId();
        clients.stream().filter(c -> id.equals(c.sessionId())).forEach(c -> c.disconnect(SseClient.SESSION_ENDED));
    }

    /** Signing in again, in another tab, gives the session a new id against fixation; its streams follow it. */
    @EventListener
    public void onSessionIdChanged(HttpSessionIdChangedEvent event) {
        String old = event.getOldSessionId();
        clients.stream().filter(c -> old.equals(c.sessionId())).forEach(c -> c.sessionId(event.getNewSessionId()));
    }

    /**
     * Ends every stream when the context closes, before the web server's graceful shutdown would wait for them;
     * later subscribers are completed at once.
     */
    @EventListener(ContextClosedEvent.class)
    public void closeAll() {
        synchronized (lock) {
            closed = true;
        }
        clients.forEach(c -> c.disconnect(SseClient.SHUTDOWN));
    }

    /** The ring's messages after {@code lastEventId}, or null when the ring cannot show that none are missing. */
    private List<SseMessage> replayAfter(long lastEventId) {
        if (!seen || lastEventId < floor || lastEventId > newest) {
            return null;
        }
        List<SseMessage> after = new ArrayList<>();
        for (SseMessage m : ring) {
            if (m.id() > lastEventId) {
                after.add(m);
            }
        }
        return after;
    }

    private void append(SseMessage m) {
        if (ring.size() == ringSize) {
            floor = ring.removeFirst().id();
        }
        ring.addLast(m);
    }

    /**
     * Null for TELEMETRY_SAMPLED and EQUIPMENT_COMMANDED, which are stored only. Equipment values and tables are the
     * current telemetry.
     */
    private SseMessage message(StoredEvent stored, LiveBoard live) {
        long id = stored.id();
        return switch (stored.event()) {
            case EquipmentEvent e -> new SseMessage("equipment", id, mapper.equipment(e.state(),
                    live.telemetry().getOrDefault(e.state().id(), Telemetry.EMPTY)));
            case ServiceEvent e -> new SseMessage("service", id, mapper.service(e.state()));
            case AlarmEvent e -> new SseMessage("alarm", id, mapper.alarm(e.alarm(), live.board()));
            case SupervisorEvent e -> new SseMessage("supervisor", id,
                    new SupervisorEventDto(e.type().name(), e.occurredAt()));
            case AlarmFloodEvent e -> {
                boolean started = e.type() == EventType.ALARM_FLOOD_STARTED;
                yield new SseMessage("flood", id, new FloodDto(started, started ? e.occurredAt() : null));
            }
            case TelemetrySampleEvent e -> null;
            case CommandEvent e -> null; // the audit trail: stored and shown in the equipment's recent events
        };
    }

    private void onClientClosed(SseClient client) {
        clients.remove(client);
        disconnects(client.closeReason()).increment();
    }

    private Counter disconnects(String reason) {
        return Counter.builder("atmsup.sse.disconnects").tag("reason", reason)
                .description("SSE clients disconnected, by reason").register(meters);
    }

    private double maxFill() {
        return clients.stream().mapToDouble(SseClient::fill).max().orElse(0);
    }

    private static Long parse(String lastEventId) {
        if (lastEventId == null) {
            return null;
        }
        try {
            return Long.valueOf(lastEventId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
