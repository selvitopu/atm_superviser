package io.github.selvitopu.atmsup.supervisor.api;

import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.selvitopu.atmsup.supervisor.api.TestEmitter.Mode;
import io.github.selvitopu.atmsup.supervisor.api.TestEmitter.Sent;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SseClientTest {

    private static final int QUEUE_SIZE = 4;

    private final List<SseClient> closed = new CopyOnWriteArrayList<>();
    private final List<SseClient> clients = new ArrayList<>();
    private final List<TestEmitter> emitters = new ArrayList<>();

    @AfterEach
    void stopClients() {
        emitters.forEach(TestEmitter::release);
        clients.forEach(c -> c.disconnect("test"));
    }

    private SseClient client(TestEmitter emitter) {
        emitters.add(emitter);
        SseClient client = new SseClient(emitter, QUEUE_SIZE, null, closed::add);
        clients.add(client);
        return client;
    }

    private static SseMessage durable(long id) {
        return new SseMessage("alarm", id, Map.of("n", id));
    }

    private static SseMessage telemetry(String equipmentId, long plotsPerScan) {
        return new SseMessage("telemetry", null, Map.of("equipmentId", equipmentId, "plotsPerScan", plotsPerScan));
    }

    /** A client whose thread is parked in the send of durable message 1, like one whose browser stopped reading. */
    private SseClient blocked(TestEmitter emitter) {
        SseClient client = client(emitter);
        client.offerDurable(durable(1));
        await().atMost(ofSeconds(1)).until(emitter::blockedInSend);
        return client;
    }

    private static List<Object> plotsPerScanSent(TestEmitter emitter) {
        return emitter.sent().stream().filter(s -> s.name().equals("telemetry"))
                .map(s -> (Object) ((Map<?, ?>) s.data()).get("plotsPerScan")).toList();
    }

    @Test
    void telemetryIsCoalescedLatestOnly() {
        TestEmitter emitter = new TestEmitter(Mode.BLOCKED);
        SseClient client = blocked(emitter);

        for (long plots = 1; plots <= 5; plots++) {
            client.putVolatile("RAD-N", telemetry("RAD-N", plots));
        }
        emitter.release();

        await().atMost(ofSeconds(1)).untilAsserted(() -> assertThat(plotsPerScanSent(emitter)).containsExactly(5L));
        assertThat(emitter.ids()).containsExactly(1L);
    }

    @Test
    void queuedDurableMessagesGoFirstInOrderThenTheVolatileSnapshot() {
        TestEmitter emitter = new TestEmitter(Mode.BLOCKED);
        SseClient client = blocked(emitter);

        client.putVolatile("RAD-N", telemetry("RAD-N", 7));
        client.offerDurable(durable(2));
        client.offerDurable(durable(3));
        client.putVolatile("heartbeat", new SseMessage("heartbeat", null, Map.of()));
        emitter.release();

        await().atMost(ofSeconds(1)).until(() -> emitter.sent().size() == 5);
        List<Sent> sent = emitter.sent();
        assertThat(sent.subList(0, 3)).extracting(Sent::id).containsExactly(1L, 2L, 3L);
        assertThat(sent.subList(3, 5)).extracting(Sent::name).containsExactlyInAnyOrder("telemetry", "heartbeat");
        assertThat(sent.subList(3, 5)).extracting(Sent::id).containsOnlyNulls();
    }

    @Test
    void aDurableMessageQueuedDuringAVolatileRoundGoesBeforeTheRestOfTheRound() {
        TestEmitter emitter = new TestEmitter(Mode.BLOCKED);
        SseClient client = blocked(emitter); // durable 1 in flight
        client.putVolatile("RAD-N", telemetry("RAD-N", 1));
        client.putVolatile("RAD-E", telemetry("RAD-E", 1));
        emitter.step(); // durable 1 goes; the round of both telemetry keys starts and blocks on its first send
        await().atMost(ofSeconds(1)).until(() -> emitter.sendsStarted() == 2);

        client.offerDurable(durable(2));
        emitter.release();

        await().atMost(ofSeconds(1)).until(() -> emitter.sent().size() == 4);
        assertThat(emitter.sent()).extracting(Sent::name)
                .containsExactly("alarm", "telemetry", "alarm", "telemetry");
        assertThat(emitter.ids()).containsExactly(1L, 2L);
    }

    @Test
    void offersNeverBlockAndAFullQueueRefusesTheNextDurableMessage() {
        TestEmitter emitter = new TestEmitter(Mode.BLOCKED);
        SseClient client = blocked(emitter); // message 1 is in flight

        long start = System.nanoTime();
        for (long id = 2; id <= 5; id++) {
            assertThat(client.offerDurable(durable(id))).isTrue();
        }
        assertThat(client.fill()).isEqualTo(1.0);
        assertThat(client.offerDurable(durable(6))).isFalse();
        for (int i = 0; i < 100; i++) {
            client.putVolatile("RAD-N", telemetry("RAD-N", i));
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(ofMillis(50));
        assertThat(client.closeReason()).isNull(); // refusing is all the client does; the caller decides
    }

    @Test
    void fillIsTheShareOfTheQueueInUse() {
        TestEmitter emitter = new TestEmitter(Mode.BLOCKED);
        SseClient client = blocked(emitter);

        client.offerDurable(durable(2));
        client.offerDurable(durable(3));

        assertThat(client.fill()).isEqualTo(0.5);
    }

    @Test
    void disconnectCompletesTheEmitterOnceAndStopsSending() {
        TestEmitter emitter = new TestEmitter(Mode.NORMAL);
        SseClient client = client(emitter);
        client.offerDurable(durable(1));
        await().atMost(ofSeconds(1)).until(() -> emitter.ids().equals(List.of(1L)));

        client.disconnect("overflow");
        client.disconnect("io");

        await().atMost(ofSeconds(1)).until(emitter::completed);
        assertThat(client.closeReason()).isEqualTo("overflow");
        assertThat(closed).containsExactly(client);
        client.offerDurable(durable(2));
        client.putVolatile("RAD-N", telemetry("RAD-N", 1));
        await().during(ofMillis(200)).atMost(ofSeconds(1)).until(() -> emitter.sent().size() == 1);
    }

    @Test
    void queuedMessagesAreNotSentAfterADisconnect() {
        TestEmitter emitter = new TestEmitter(Mode.BLOCKED);
        SseClient client = blocked(emitter);
        client.offerDurable(durable(2));
        client.offerDurable(durable(3));

        client.disconnect("overflow");
        emitter.release();

        await().atMost(ofSeconds(1)).until(emitter::completed);
        assertThat(emitter.ids()).containsExactly(1L); // only the send that was already in progress
    }

    @Test
    void aFailedSendDisconnectsWithReasonIoAndLeavesCompletionToTheContainer() {
        TestEmitter emitter = new TestEmitter(Mode.FAILING);
        SseClient client = client(emitter);

        client.offerDurable(durable(1));

        await().atMost(ofSeconds(1)).until(() -> client.closeReason() != null);
        assertThat(client.closeReason()).isEqualTo("io");
        assertThat(closed).containsExactly(client);
        await().during(ofMillis(200)).atMost(ofSeconds(1)).until(() -> !emitter.completed());
    }

    @Test
    void aTimeoutDisconnectsAndCompletesTheEmitterAtOnce() {
        TestEmitter emitter = new TestEmitter(Mode.NORMAL);
        SseClient client = client(emitter);

        emitter.fireTimeout();

        assertThat(client.closeReason()).isEqualTo("timeout");
        assertThat(emitter.completed()).isTrue();
        assertThat(closed).containsExactly(client);
    }

    @Test
    void aContainerErrorDisconnectsWithReasonIo() {
        TestEmitter emitter = new TestEmitter(Mode.NORMAL);
        SseClient client = client(emitter);

        emitter.fireError(new IOException("Connection reset by peer"));

        assertThat(client.closeReason()).isEqualTo("io");
        assertThat(closed).containsExactly(client);
        await().during(ofMillis(200)).atMost(ofSeconds(1)).until(() -> !emitter.completed());
    }
}
