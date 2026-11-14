package io.github.selvitopu.atmsup.supervisor.polling;

import static io.github.selvitopu.atmsup.supervisor.polling.PollDispatcher.Priority.PROBE;
import static io.github.selvitopu.atmsup.supervisor.polling.PollDispatcher.Priority.SCHEDULED_FULL;
import static io.github.selvitopu.atmsup.supervisor.polling.PollDispatcher.Priority.TRAP_REFRESH;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import io.github.selvitopu.atmsup.supervisor.CapturedLogs;
import io.github.selvitopu.atmsup.supervisor.polling.PollDispatcher.Priority;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PollDispatcherTest {

    private static final Duration SHORT = Duration.ofMillis(100);

    private PollDispatcher dispatcher;

    @AfterEach
    void close() {
        if (dispatcher != null) {
            dispatcher.close();
        }
    }

    /** Submits a job that holds the only worker until {@code release} opens, and waits until it runs. */
    private void occupyTheWorker(CountDownLatch release) throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        dispatcher.submit(PROBE, () -> {
            started.countDown();
            awaitQuietly(release);
        });
        assertThat(started.await(5, SECONDS)).isTrue();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void priorityOrder() throws Exception {
        dispatcher = new PollDispatcher(1);
        CountDownLatch release = new CountDownLatch(1);
        occupyTheWorker(release);
        List<Priority> order = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(3);
        for (Priority p : List.of(SCHEDULED_FULL, PROBE, TRAP_REFRESH)) {
            dispatcher.submit(p, () -> {
                order.add(p);
                done.countDown();
            });
            if (p == SCHEDULED_FULL) {
                Thread.sleep(50); // a dispatcher that took jobs before a slot is free would take this one now
            }
        }

        release.countDown();

        assertThat(done.await(5, SECONDS)).isTrue();
        assertThat(order).containsExactly(TRAP_REFRESH, PROBE, SCHEDULED_FULL);
    }

    @Test
    void jobsOfOnePriorityRunInSubmissionOrder() throws Exception {
        dispatcher = new PollDispatcher(1);
        CountDownLatch release = new CountDownLatch(1);
        occupyTheWorker(release);
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(3);
        for (String name : List.of("a", "b", "c")) {
            dispatcher.submit(PROBE, () -> {
                order.add(name);
                done.countDown();
            });
        }

        release.countDown();

        assertThat(done.await(5, SECONDS)).isTrue();
        assertThat(order).containsExactly("a", "b", "c");
    }

    @Test
    void atMostMaxConcurrentJobs() throws Exception {
        dispatcher = new PollDispatcher(8);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(16);
        for (int i = 0; i < 16; i++) {
            dispatcher.submit(i % 2 == 0 ? PROBE : SCHEDULED_FULL, () -> {
                peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                awaitQuietly(release);
                inFlight.decrementAndGet();
                done.countDown();
            });
        }

        await().atMost(Duration.ofSeconds(5)).until(() -> inFlight.get() == 8);
        await().during(SHORT).atMost(Duration.ofSeconds(1)).until(() -> inFlight.get() == 8); // no ninth job starts
        release.countDown();

        assertThat(done.await(5, SECONDS)).isTrue();
        assertThat(peak.get()).isEqualTo(8);
    }

    @Test
    void aFailingJobIsLoggedAndReleasesItsSlot() throws Exception {
        dispatcher = new PollDispatcher(1);
        try (CapturedLogs logs = new CapturedLogs(PollDispatcher.class)) {
            dispatcher.submit(PROBE, () -> {
                throw new IllegalStateException("boom");
            });
            CountDownLatch next = new CountDownLatch(1);
            dispatcher.submit(PROBE, next::countDown);

            assertThat(next.await(5, SECONDS)).isTrue();
            assertThat(logs.events()).singleElement().satisfies(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                assertThat(e.getThrowableProxy().getMessage()).isEqualTo("boom");
            });
        }
    }

    @Test
    void closeWaitsForRunningJobsAndDropsQueuedAndLaterOnes() throws Exception {
        dispatcher = new PollDispatcher(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        dispatcher.submit(PROBE, () -> {
            started.countDown();
            awaitQuietly(release);
            finished.set(true);
        });
        assertThat(started.await(5, SECONDS)).isTrue();
        AtomicBoolean queuedRan = new AtomicBoolean();
        dispatcher.submit(TRAP_REFRESH, () -> queuedRan.set(true));

        CompletableFuture<Void> closing = CompletableFuture.runAsync(dispatcher::close);
        await().during(SHORT).atMost(Duration.ofSeconds(1)).until(() -> !closing.isDone());
        release.countDown();
        closing.get(5, SECONDS);

        assertThat(finished).isTrue();
        AtomicBoolean lateRan = new AtomicBoolean();
        dispatcher.submit(TRAP_REFRESH, () -> lateRan.set(true));
        await().during(SHORT).atMost(Duration.ofSeconds(1)).until(() -> !queuedRan.get() && !lateRan.get());
        dispatcher.close(); // idempotent, and returns at once
    }
}
