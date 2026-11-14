package io.github.selvitopu.atmsup.supervisor.api;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Stands in for the servlet response: it records what a client sends instead of writing it. A blocked emitter parks
 * every send until {@link #release()}, like a browser that stopped reading, and holds the write lock meanwhile as a
 * real send does; a failing one throws as a broken pipe does. The container callbacks are kept so that a test can
 * fire them.
 */
final class TestEmitter extends SseEmitter {

    /** One recorded event; {@code id} is null for telemetry, heartbeat and resync. */
    record Sent(String name, Long id, Object data) {
    }

    enum Mode { NORMAL, BLOCKED, FAILING }

    private static final long MAX_BLOCK_SECONDS = 10;

    private final Mode mode;
    private final Semaphore gate = new Semaphore(0);
    private final AtomicInteger sendsStarted = new AtomicInteger();
    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private volatile boolean open;
    private volatile boolean blockedInSend;
    private volatile boolean completed;
    private volatile Runnable timeoutCallback;
    private volatile Consumer<Throwable> errorCallback;

    TestEmitter(Mode mode) {
        this.mode = mode;
    }

    /** Like the real send, this holds the emitter's write lock for the whole write, blocked or not. */
    @Override
    public void send(SseEventBuilder builder) throws IOException {
        Set<DataWithMediaType> parts = builder.build();
        writeLock.lock();
        try {
            if (completed) {
                throw new IllegalStateException("ResponseBodyEmitter has already completed");
            }
            switch (mode) {
                case FAILING -> throw new IOException("Broken pipe");
                case BLOCKED -> {
                    sendsStarted.incrementAndGet();
                    blockedInSend = true;
                    awaitGate();
                    blockedInSend = false;
                }
                case NORMAL -> {
                }
            }
            sent.add(parse(parts));
        } finally {
            writeLock.unlock();
        }
    }

    /** Takes the write lock, as the real one does: it waits for a send in progress. */
    @Override
    public void complete() {
        writeLock.lock();
        try {
            completed = true;
            super.complete();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void completeWithError(Throwable ex) {
        writeLock.lock();
        try {
            completed = true;
            super.completeWithError(ex);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void onTimeout(Runnable callback) {
        timeoutCallback = callback;
        super.onTimeout(callback);
    }

    @Override
    public void onError(Consumer<Throwable> callback) {
        errorCallback = callback;
        super.onError(callback);
    }

    /** What the container does when the request times out. */
    void fireTimeout() {
        timeoutCallback.run();
    }

    /** What the container does when the connection fails. */
    void fireError(Throwable t) {
        errorCallback.accept(t);
    }

    /** Unblocks for good: the send in progress and every later one go through. */
    void release() {
        open = true;
        gate.release(); // wakes the send in progress, if any: there is only ever one, on the client's thread
    }

    /** Lets exactly one blocked send through. */
    void step() {
        gate.release();
    }

    boolean blockedInSend() {
        return blockedInSend;
    }

    int sendsStarted() {
        return sendsStarted.get();
    }

    /** True once the client has completed the emitter: its stream is closed. */
    boolean completed() {
        return completed;
    }

    List<Sent> sent() {
        return List.copyOf(sent);
    }

    List<Long> ids() {
        return sent.stream().map(Sent::id).filter(Objects::nonNull).toList();
    }

    /** Parses "id:..\nevent:..\ndata:" text parts; the data object is the one non-text part. */
    private static Sent parse(Set<DataWithMediaType> parts) {
        String name = null;
        Long id = null;
        Object data = null;
        for (DataWithMediaType part : parts) {
            if (part.getData() instanceof String text) {
                for (String line : text.split("\n")) {
                    if (line.startsWith("id:")) {
                        id = Long.valueOf(line.substring(3));
                    } else if (line.startsWith("event:")) {
                        name = line.substring(6);
                    }
                }
            } else {
                data = part.getData();
            }
        }
        return new Sent(name, id, data);
    }

    private void awaitGate() {
        if (open) {
            return;
        }
        boolean interrupted = false;
        while (true) {
            try {
                gate.tryAcquire(MAX_BLOCK_SECONDS, TimeUnit.SECONDS); // bounded: a forgotten release cannot hang a test
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
