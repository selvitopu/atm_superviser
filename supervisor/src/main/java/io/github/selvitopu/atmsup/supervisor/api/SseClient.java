package io.github.selvitopu.atmsup.supervisor.api;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

/**
 * One SSE subscriber (amendment §4). Producers only enqueue and never block: durable messages go into a bounded FIFO
 * queue, volatile ones into a latest-only map keyed by equipment id (or {@code heartbeat}), so a slow client only
 * ever gets the newest sample. One virtual thread per client does all the writing: the queued durable messages in
 * order, then the volatile snapshot. A volatile message is sent only while no durable one is waiting, so it never
 * overtakes a durable message queued before it.
 *
 * <p>The thread also owns completion. {@link #disconnect} returns at once: it detaches the client, drops what is
 * queued and wakes the thread, which completes the emitter as soon as no send is in progress. Spring's emitter holds
 * a lock for the whole of a send, so completing it from any other thread could wait on a stuck socket.
 */
final class SseClient {

    /** Disconnect reasons, the {@code reason} tag of {@code atmsup.sse.disconnects}. */
    static final String OVERFLOW = "overflow";
    static final String IO = "io";
    static final String TIMEOUT = "timeout";
    static final String SHUTDOWN = "shutdown";
    /** The client's session ended: signed out, expired, or ended by an administrator. */
    static final String SESSION_ENDED = "session-ended";
    /** The broadcaster could not build a commit's messages: the client must reconnect and resync. */
    static final String ERROR = "error";

    private static final Logger log = LoggerFactory.getLogger(SseClient.class);
    private static final AtomicLong NUMBERS = new AtomicLong();

    private final SseEmitter emitter;
    private final int queueSize;
    private final BlockingQueue<SseMessage> durable;
    private final Map<String, SseMessage> latest = new ConcurrentHashMap<>();
    private final Consumer<SseClient> onClose;
    private final String sessionId;
    /** Wakes the idle thread; at most a few permits are ever pending, and they are drained on every wake-up. */
    private final Semaphore wake = new Semaphore(0);
    private final AtomicReference<String> closeReason = new AtomicReference<>();
    /** Set once the emitter is completed, by this client or by the container: it is never completed twice. */
    private final AtomicBoolean finished = new AtomicBoolean();

    /**
     * Starts the client's thread at once; messages sent before Spring has initialised the emitter are buffered by it.
     *
     * @param sessionId the HTTP session the stream belongs to, or null
     * @param onClose called exactly once, on the thread that disconnects the client; it must not block
     */
    SseClient(SseEmitter emitter, int queueSize, String sessionId, Consumer<SseClient> onClose) {
        this.emitter = emitter;
        this.sessionId = sessionId;
        this.queueSize = queueSize;
        this.durable = new ArrayBlockingQueue<>(queueSize);
        this.onClose = onClose;
        emitter.onTimeout(() -> { // on a container thread: completing within the callback ends the request normally
            boolean completeHere = finished.compareAndSet(false, true);
            disconnect(TIMEOUT);
            if (completeHere) {
                emitter.complete();
            }
        });
        emitter.onError(e -> { // the container completes a request that failed
            finished.set(true);
            disconnect(IO);
        });
        emitter.onCompletion(() -> {
            finished.set(true);
            disconnect(IO);
        });
        Thread.ofVirtual().name("sse-client-" + NUMBERS.incrementAndGet()).start(this::run);
    }

    /**
     * Queues a message that must arrive, in order. Never blocks.
     *
     * @return false when the queue is full; a client that is already disconnected accepts and drops it
     */
    boolean offerDurable(SseMessage m) {
        if (!isOpen()) {
            return true;
        }
        if (!durable.offer(m)) {
            return false;
        }
        signal();
        return true;
    }

    /** Replaces any unsent message with the same key. Never blocks. */
    void putVolatile(String key, SseMessage m) {
        if (!isOpen()) {
            return;
        }
        latest.put(key, m);
        signal();
    }

    /**
     * Stops delivery at once and, on the client's thread, completes the emitter and ends the thread. Only the first
     * call counts; it calls {@code onClose}.
     */
    String sessionId() {
        return sessionId;
    }

    void disconnect(String reason) {
        if (!closeReason.compareAndSet(null, reason)) {
            return;
        }
        durable.clear();
        latest.clear();
        signal();
        onClose.accept(this);
    }

    /** Null while the client is connected. */
    String closeReason() {
        return closeReason.get();
    }

    /** The share of the durable queue in use, from 0 to 1. */
    double fill() {
        return durable.size() / (double) queueSize;
    }

    private boolean isOpen() {
        return closeReason.get() == null;
    }

    private void signal() {
        if (wake.availablePermits() == 0) {
            wake.release();
        }
    }

    private void run() {
        try {
            while (isOpen()) {
                sendQueued();
                if (latest.isEmpty()) {
                    wake.acquire();
                    wake.drainPermits();
                    continue;
                }
                for (String key : List.copyOf(latest.keySet())) { // one round: every key gets its turn
                    sendQueued();
                    SseMessage m = latest.remove(key);
                    if (m != null) {
                        send(m);
                    }
                }
            }
        } catch (IOException e) {
            finished.set(true); // the container completes the request after a failed write
            disconnect(IO);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            disconnect(IO);
        } catch (RuntimeException e) {
            if (isOpen()) {
                log.warn("Cannot send to an SSE client; disconnecting it", e);
            }
            disconnect(IO);
        } finally {
            complete();
        }
    }

    private void sendQueued() throws IOException {
        SseMessage m;
        while (isOpen() && (m = durable.poll()) != null) {
            send(m);
        }
    }

    private void send(SseMessage m) throws IOException {
        if (!isOpen()) {
            return;
        }
        SseEventBuilder event = SseEmitter.event();
        if (m.id() != null) {
            event.id(String.valueOf(m.id()));
        }
        emitter.send(event.name(m.name()).data(m.data(), MediaType.APPLICATION_JSON));
    }

    private void complete() {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        try {
            emitter.complete();
        } catch (RuntimeException e) {
            log.warn("Cannot complete an SSE stream", e);
        }
    }
}
