package io.github.selvitopu.atmsup.supervisor.polling;

import java.time.Duration;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs SNMP jobs (probes and full polls together) on virtual threads, at most {@code maxConcurrent} at a time, so a
 * trap flood or many unreachable devices cannot start unbounded work (amendment §2). Waiting jobs are taken by
 * priority, then in submission order. A job that throws is logged and still frees its slot.
 */
public final class PollDispatcher implements AutoCloseable {

    /** In descending priority. */
    public enum Priority { TRAP_REFRESH, PROBE, SCHEDULED_FULL }

    private static final Logger log = LoggerFactory.getLogger(PollDispatcher.class);
    /** How long close waits for running jobs; one request takes at most 3 s (1500 ms timeout, 1 retry). */
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(10);

    private record Job(Priority priority, long sequence, Runnable task) implements Comparable<Job> {

        @Override
        public int compareTo(Job other) {
            int byPriority = priority.compareTo(other.priority);
            return byPriority != 0 ? byPriority : Long.compare(sequence, other.sequence);
        }
    }

    private final int maxConcurrent;
    private final Semaphore slots;
    private final PriorityBlockingQueue<Job> queue = new PriorityBlockingQueue<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ThreadFactory jobThreads = Thread.ofVirtual().name("poll-job-", 0).factory();
    private final Thread dispatcher;

    public PollDispatcher(int maxConcurrent) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent must be at least 1, was " + maxConcurrent);
        }
        this.maxConcurrent = maxConcurrent;
        this.slots = new Semaphore(maxConcurrent);
        this.dispatcher = Thread.ofVirtual().name("poll-dispatcher").start(this::dispatch);
    }

    /** Queues the job; it is dropped if the dispatcher is closed. Never blocks. */
    public void submit(Priority p, Runnable job) {
        if (!closed.get()) {
            queue.add(new Job(p, sequence.getAndIncrement(), job));
        }
    }

    /** A free slot is taken first, so the job chosen is the most urgent one waiting when it can actually start. */
    private void dispatch() {
        try {
            while (true) {
                slots.acquire();
                Job job;
                try {
                    job = queue.take();
                } catch (InterruptedException e) {
                    slots.release();
                    throw e;
                }
                if (closed.get()) {
                    slots.release();
                    return;
                }
                start(job);
            }
        } catch (InterruptedException e) {
            // closed
        }
    }

    private void start(Job job) {
        try {
            jobThreads.newThread(() -> run(job)).start();
        } catch (RuntimeException e) {
            slots.release();
            log.error("Cannot start a {} poll job", job.priority(), e);
        }
    }

    private void run(Job job) {
        try {
            job.task().run();
        } catch (RuntimeException e) {
            log.error("A {} poll job failed", job.priority(), e);
        } finally {
            slots.release();
        }
    }

    /**
     * Stops taking jobs, drops the queued ones and waits (bounded) for the running ones to finish, so no SNMP request
     * is still in flight when the session closes. Idempotent.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        dispatcher.interrupt();
        queue.clear();
        try {
            if (!slots.tryAcquire(maxConcurrent, CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Poll jobs still running {} after close", CLOSE_TIMEOUT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
