package io.github.selvitopu.atmsup.supervisor.api;

import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.simulator.fixture.TestInventories;
import io.github.selvitopu.atmsup.supervisor.CapturedLogs;
import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmKey;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmSource;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmState;
import io.github.selvitopu.atmsup.supervisor.alarms.Via;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.AlarmDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.EquipmentDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.HeartbeatDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.ServiceDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.SupervisorEventDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.TelemetryDto;
import io.github.selvitopu.atmsup.supervisor.api.TestEmitter.Mode;
import io.github.selvitopu.atmsup.supervisor.api.TestEmitter.Sent;
import io.github.selvitopu.atmsup.supervisor.board.Board;
import io.github.selvitopu.atmsup.supervisor.board.BoardEvents.BoardCommitted;
import io.github.selvitopu.atmsup.supervisor.board.BoardEvents.TelemetryUpdated;
import io.github.selvitopu.atmsup.supervisor.board.BoardWriter;
import io.github.selvitopu.atmsup.supervisor.board.Contact;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.AlarmEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.EquipmentEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.ServiceEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.SupervisorEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.TelemetrySampleEvent;
import io.github.selvitopu.atmsup.supervisor.board.EquipmentState;
import io.github.selvitopu.atmsup.supervisor.board.EventType;
import io.github.selvitopu.atmsup.supervisor.board.Health;
import io.github.selvitopu.atmsup.supervisor.board.LiveBoard;
import io.github.selvitopu.atmsup.supervisor.board.ServiceId;
import io.github.selvitopu.atmsup.supervisor.board.ServiceState;
import io.github.selvitopu.atmsup.supervisor.board.ServiceStatus;
import io.github.selvitopu.atmsup.supervisor.board.StoredEvent;
import io.github.selvitopu.atmsup.supervisor.board.Telemetry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Review focus 5 (amendment §4): ring filled with ids 101..1100; test emitters record what each client sends. */
class SseBroadcasterTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    private final Inventory inventory = Inventory.from(TestInventories.v01(16101, 10162));
    private final DtoMapper mapper = new DtoMapper(inventory);
    private final BoardWriter writer = mock(BoardWriter.class);
    private final Telemetry tel = new Telemetry(T0.minusSeconds(1), T0, 2, 0, null, Map.of("plotsPerScan", 300L),
            Map.of("sensors", List.of(Map.of("index", 1L))));
    private final LiveBoard live = new LiveBoard(Board.initial(inventory, List.of()), Map.of("RAD-N", tel), Map.of());
    private final List<TestEmitter> emitters = new CopyOnWriteArrayList<>();

    private SimpleMeterRegistry meters;
    private SseBroadcaster broadcaster;
    private Mode nextMode = Mode.NORMAL;
    private TestEmitter client;

    @BeforeEach
    void setUp() {
        given(writer.current()).willReturn(live);
        broadcaster = broadcaster(1000, 256);
        broadcaster.onCommitted(committed(LongStream.rangeClosed(101, 1100)
                .mapToObj(id -> stored(id, alarmEvent())).toArray(StoredEvent[]::new)));
        client = normalClient();
    }

    @AfterEach
    void tearDown() {
        emitters.forEach(TestEmitter::release);
        broadcaster.closeAll();
    }

    // --- fixtures -----------------------------------------------------------------------------------------------

    private SseBroadcaster broadcaster(int ringSize, int clientQueueSize) {
        if (broadcaster != null) {
            broadcaster.closeAll();
        }
        meters = new SimpleMeterRegistry();
        return new SseBroadcaster(mapper, writer, InstantSource.fixed(T0), meters, ringSize, clientQueueSize,
                () -> {
                    TestEmitter e = new TestEmitter(nextMode);
                    emitters.add(e);
                    return e;
                });
    }

    private TestEmitter subscribe(Mode mode, String header, String param) {
        nextMode = mode;
        return (TestEmitter) broadcaster.subscribe(header, param);
    }

    private TestEmitter normalClient() {
        return subscribe(Mode.NORMAL, null, null);
    }

    /** A client whose browser stopped reading: every send parks until the emitter is released. */
    private TestEmitter blockedClient() {
        return subscribe(Mode.BLOCKED, null, null);
    }

    private static StoredEvent stored(long id, DomainEvent e) {
        return new StoredEvent(id, e);
    }

    private BoardCommitted committed(StoredEvent... events) {
        return new BoardCommitted(List.of(events), live);
    }

    private static AlarmEvent alarmEvent() {
        return new AlarmEvent(EventType.ALARM_RAISED, T0, new Alarm("a1", new AlarmKey("RAD-N", 101, 0), "",
                Severity.CRITICAL, "Radar failure", AlarmSource.DEVICE, AlarmState.ACTIVE_UNACK, false, T0, null, null,
                null, null), Via.TRAP, null);
    }

    private static TelemetrySampleEvent telemetrySample(String equipmentId) {
        return new TelemetrySampleEvent(T0, equipmentId, T0, Map.of("plotsPerScan", 300L), Map.of());
    }

    /**
     * Everything the client has sent so far, except heartbeats. A heartbeat is the barrier: the client sends a
     * volatile message only when no durable one is queued, so once it is recorded every earlier durable message is.
     */
    private List<Sent> sent(SseEmitter emitter) {
        TestEmitter e = (TestEmitter) emitter;
        long before = heartbeats(e);
        broadcaster.heartbeat();
        await().atMost(ofSeconds(1)).until(() -> heartbeats(e) > before);
        return e.sent().stream().filter(s -> !s.name().equals("heartbeat")).toList();
    }

    private static long heartbeats(TestEmitter e) {
        return e.sent().stream().filter(s -> s.name().equals("heartbeat")).count();
    }

    private List<Long> sentIds(SseEmitter emitter) {
        return sent(emitter).stream().map(Sent::id).filter(id -> id != null).toList();
    }

    private List<String> sentNames(SseEmitter emitter) {
        return sent(emitter).stream().map(Sent::name).toList();
    }

    private double counter(String name, String... tags) {
        return meters.counter(name, tags).count();
    }

    private double gauge(String name) {
        return meters.get(name).gauge().value();
    }

    private static List<Long> ids(long from, long to) {
        return LongStream.rangeClosed(from, to).boxed().toList();
    }

    // --- resume -------------------------------------------------------------------------------------------------

    @Test
    void resumesAfterKnownId() {
        assertThat(sentIds(subscribe(Mode.NORMAL, null, "1095"))).containsExactly(1096L, 1097L, 1098L, 1099L, 1100L);
    }

    @Test
    void headerWinsOverParam() {
        assertThat(sentIds(subscribe(Mode.NORMAL, "1099", "1000"))).containsExactly(1100L);
    }

    @Test
    void theOldestBufferedEventCanStillBeResumedFrom() {
        broadcaster = broadcaster(10, 256);
        broadcaster.onCommitted(committed(LongStream.rangeClosed(1, 15)
                .mapToObj(id -> stored(id, alarmEvent())).toArray(StoredEvent[]::new))); // the ring keeps 6..15

        assertThat(sentIds(subscribe(Mode.NORMAL, null, "5"))).isEqualTo(ids(6, 15));
        assertThat(sentNames(subscribe(Mode.NORMAL, null, "4"))).containsExactly("resync");
    }

    @Test
    void tooOldGetsResync() {
        assertThat(sentNames(subscribe(Mode.NORMAL, null, "50"))).containsExactly("resync");
        assertThat(counter("atmsup.sse.resyncs")).isEqualTo(1.0);
    }

    @Test
    void aheadOfServerGetsResync() {
        assertThat(sentNames(subscribe(Mode.NORMAL, null, "5000"))).containsExactly("resync");
    }

    @Test
    void aReplayThatDoesNotFitTheQueueGetsResync() {
        TestEmitter late = subscribe(Mode.NORMAL, null, "100"); // 1000 events behind, the queue holds 256

        List<Sent> sent = sent(late);
        assertThat(sent).extracting(Sent::name, Sent::id).containsExactly(tuple("resync", null));
        assertThat(sent.getFirst().data()).isEqualTo(Map.of());
        assertThat(counter("atmsup.sse.resyncs")).isEqualTo(1.0);
    }

    @Test
    void garbageIsTreatedAsAbsent() {
        assertThat(sentNames(subscribe(Mode.NORMAL, "abc", null))).isEmpty();
    }

    @Test
    void theIdOfAStoredOnlySampleIsNotAheadOfTheServer() {
        broadcaster.onCommitted(committed(stored(1101, telemetrySample("RAD-N")))); // /board says lastEventId 1101

        assertThat(sentNames(subscribe(Mode.NORMAL, null, "1101"))).isEmpty();
        assertThat(sentNames(subscribe(Mode.NORMAL, null, "1100"))).isEmpty();
        assertThat(counter("atmsup.sse.resyncs")).isZero();
    }

    @Test
    void beforeAnyCommitEveryResumeIsAResync() {
        broadcaster = broadcaster(1000, 256);

        assertThat(sentNames(subscribe(Mode.NORMAL, null, "7"))).containsExactly("resync");
        assertThat(sentNames(subscribe(Mode.NORMAL, null, null))).isEmpty();
    }

    // --- messages -----------------------------------------------------------------------------------------------

    @Test
    void telemetryHasNoId() {
        broadcaster.onTelemetry(new TelemetryUpdated("RAD-N", tel, T0));

        await().atMost(ofSeconds(1)).untilAsserted(() -> assertThat(client.sent())
                .filteredOn(s -> s.name().equals("telemetry")).singleElement().satisfies(s -> {
                    assertThat(s.id()).isNull();
                    assertThat(s.data()).isEqualTo(new TelemetryDto("RAD-N", T0.minusSeconds(1), 2,
                            Map.of("plotsPerScan", 300L), Map.of("sensors", List.of(Map.of("index", 1L))), T0));
                }));
    }

    @Test
    void heartbeatHasNoIdAndCarriesTheServerTime() {
        broadcaster.heartbeat();

        await().atMost(ofSeconds(1)).untilAsserted(() -> assertThat(client.sent()).singleElement()
                .isEqualTo(new Sent("heartbeat", null, new HeartbeatDto(T0))));
    }

    @Test
    void telemetrySampledIsNotBroadcast() {
        broadcaster.onCommitted(committed(stored(1101, telemetrySample("RAD-N")), stored(1102, alarmEvent())));

        assertThat(sentIds(client)).containsExactly(1102L);
    }

    @Test
    void durableMessagesCarryTheEntityStateAndCurrentTelemetry() {
        EquipmentState failed = new EquipmentState("RAD-N", Health.FAILED, null, false, Role.STANDALONE, T0,
                new Contact(true, T0, null));
        ServiceState surv = new ServiceState(ServiceId.SURV, ServiceStatus.OK, null, false, List.of(), T0);
        AlarmEvent alarm = alarmEvent();

        broadcaster.onCommitted(committed(
                stored(1101, new EquipmentEvent(EventType.EQUIPMENT_STATE_CHANGED, T0, failed)),
                stored(1102, new ServiceEvent(T0, surv)),
                stored(1103, alarm),
                stored(1104, new SupervisorEvent(EventType.SUPERVISOR_HEARTBEAT, T0, null))));

        List<Sent> sent = sent(client);
        assertThat(sent).extracting(Sent::name).containsExactly("equipment", "service", "alarm", "supervisor");
        assertThat(sent).extracting(Sent::id).containsExactly(1101L, 1102L, 1103L, 1104L);
        assertThat(sent.get(0).data()).isEqualTo(mapper.equipment(failed, tel));
        assertThat((EquipmentDto) sent.get(0).data()).satisfies(eq -> {
            assertThat(eq.health()).isEqualTo("FAILED"); // the event's state, not the board's (RAD-N is UNKNOWN there)
            assertThat(eq.values()).containsEntry("plotsPerScan", 300L);
            assertThat(eq.tables()).containsKey("sensors");
        });
        assertThat(sent.get(1).data()).isEqualTo(mapper.service(surv)).isInstanceOf(ServiceDto.class);
        assertThat(sent.get(2).data()).isEqualTo(mapper.alarm(alarm.alarm(), live.board()))
                .isInstanceOf(AlarmDto.class);
        assertThat(sent.get(3).data()).isEqualTo(new SupervisorEventDto("SUPERVISOR_HEARTBEAT", T0));
    }

    // --- delivery -----------------------------------------------------------------------------------------------

    @Test
    void slowClientDoesNotDelayOthers() {                                                      // amendment §4
        TestEmitter slow = blockedClient();
        TestEmitter fast = normalClient();
        broadcaster.onCommitted(committed(stored(1101, alarmEvent())));
        await().atMost(ofSeconds(1)).until(slow::blockedInSend);

        long t = System.nanoTime();
        broadcaster.onCommitted(committed(stored(1102, alarmEvent()), stored(1103, alarmEvent())));
        assertThat(Duration.ofNanos(System.nanoTime() - t)).isLessThan(ofMillis(50));

        await().atMost(ofSeconds(1)).until(() -> fast.ids().containsAll(List.of(1101L, 1102L, 1103L)));
        assertThat(slow.ids()).isEmpty();
        slow.release();
        assertThat(sentIds(slow)).containsExactly(1101L, 1102L, 1103L); // slow, not lost
    }

    @Test
    void overflowDisconnectsInsteadOfDropping() {                                         // queue size 4 in this test
        broadcaster = broadcaster(1000, 4);
        TestEmitter slow = blockedClient();
        broadcaster.onCommitted(committed(stored(1101, alarmEvent())));
        await().atMost(ofSeconds(1)).until(slow::blockedInSend); // 1101 is in flight

        long t = System.nanoTime();
        for (long id = 1102; id <= 1110; id++) {
            broadcaster.onCommitted(committed(stored(id, alarmEvent()))); // 1102..1105 fill the queue, 1106 does not fit
        }
        assertThat(Duration.ofNanos(System.nanoTime() - t)).isLessThan(ofMillis(50)); // the writer never waits

        assertThat(counter("atmsup.sse.disconnects", "reason", "overflow")).isEqualTo(1.0);
        assertThat(gauge("atmsup.sse.clients")).isZero(); // detached at once: nothing more is queued for it
        slow.release();
        await().atMost(ofSeconds(1)).until(slow::completed);
        assertThat(slow.ids()).containsExactly(1101L); // the browser resumes from 1101 on reconnect
    }

    @Test
    void durableIdsArriveInOrder() throws Exception {
        Thread telemetry = Thread.ofVirtual().start(() -> {
            for (int i = 0; i < 200; i++) {
                broadcaster.onTelemetry(new TelemetryUpdated(i % 2 == 0 ? "RAD-N" : "RAD-E", tel, T0));
            }
        });
        long id = 1101;
        while (id <= 1150) { // commits of 1 to 3 events
            int size = 1 + (int) (id % 3);
            List<StoredEvent> batch = new ArrayList<>();
            for (int n = 0; n < size && id <= 1150; n++) {
                batch.add(stored(id++, alarmEvent()));
            }
            broadcaster.onCommitted(committed(batch.toArray(StoredEvent[]::new)));
        }
        telemetry.join(Duration.ofSeconds(5));

        assertThat(sentIds(client)).isEqualTo(ids(1101, 1150)); // strictly ascending, no gaps, no duplicates
    }

    @Test
    void aSubscriberResumingDuringCommitsMissesNothingAndGetsNothingTwice() throws Exception {
        broadcaster = broadcaster(1000, 1024);
        broadcaster.onCommitted(committed(LongStream.rangeClosed(101, 1100)
                .mapToObj(id -> stored(id, alarmEvent())).toArray(StoredEvent[]::new)));
        CountDownLatch halfway = new CountDownLatch(1);
        Thread writerThread = Thread.ofPlatform().start(() -> {
            for (long id = 1101; id <= 1600; id++) {
                broadcaster.onCommitted(committed(stored(id, alarmEvent())));
                if (id == 1350) {
                    halfway.countDown();
                }
            }
        });

        assertThat(halfway.await(5, TimeUnit.SECONDS)).isTrue();
        TestEmitter resumed = subscribe(Mode.NORMAL, "1100", null);
        writerThread.join(Duration.ofSeconds(5));

        assertThat(sentIds(resumed)).isEqualTo(ids(1101, 1600));
    }

    @Test
    void aFailedSendRemovesTheClient() {
        subscribe(Mode.FAILING, null, null);
        assertThat(gauge("atmsup.sse.clients")).isEqualTo(2.0);

        broadcaster.onCommitted(committed(stored(1101, alarmEvent())));

        await().atMost(ofSeconds(1)).until(() -> counter("atmsup.sse.disconnects", "reason", "io") == 1.0);
        assertThat(gauge("atmsup.sse.clients")).isEqualTo(1.0);
    }

    /** An equipment event for a device the inventory does not know: mapping it throws. */
    private static EquipmentEvent unmappable() {
        return new EquipmentEvent(EventType.EQUIPMENT_STATE_CHANGED, T0, new EquipmentState("XYZ", Health.FAILED,
                null, false, Role.STANDALONE, T0, new Contact(true, T0, null)));
    }

    @Test
    void aCommitThatCannotBeMappedDisconnectsEveryClientInsteadOfDroppingIt() {
        TestEmitter other = normalClient();

        try (CapturedLogs logs = new CapturedLogs(SseBroadcaster.class)) {
            broadcaster.onCommitted(committed(stored(1101, alarmEvent()), stored(1102, unmappable()),
                    stored(1103, alarmEvent())));
            assertThat(logs.events()).extracting(ILoggingEvent::getLevel).containsExactly(Level.ERROR);
        }

        assertThat(counter("atmsup.sse.disconnects", "reason", "error")).isEqualTo(2.0);
        assertThat(gauge("atmsup.sse.clients")).isZero();
        await().atMost(ofSeconds(1)).until(() -> client.completed() && other.completed());
        assertThat(client.ids()).isEmpty(); // nothing of the commit was sent: the browsers resume from 1100
        assertThat(sentNames(subscribe(Mode.NORMAL, null, "1100"))).containsExactly("resync");
        assertThat(sentNames(subscribe(Mode.NORMAL, null, "1103"))).isEmpty(); // /board already has the commit
        broadcaster.onCommitted(committed(stored(1104, alarmEvent())));
        assertThat(sentIds(subscribe(Mode.NORMAL, null, "1103"))).containsExactly(1104L);
    }

    @Test
    void noResumeCrossesAMappingGapEvenAfterTheRingWrapsAround() {
        broadcaster = broadcaster(10, 256);
        broadcaster.onCommitted(committed(LongStream.rangeClosed(1, 15)
                .mapToObj(id -> stored(id, alarmEvent())).toArray(StoredEvent[]::new))); // the ring keeps 6..15
        try (CapturedLogs logs = new CapturedLogs(SseBroadcaster.class)) {
            broadcaster.onCommitted(committed(stored(16, unmappable()), stored(17, alarmEvent())));
        }

        broadcaster.onCommitted(committed(LongStream.rangeClosed(18, 22)
                .mapToObj(id -> stored(id, alarmEvent())).toArray(StoredEvent[]::new)));

        assertThat(sentNames(subscribe(Mode.NORMAL, null, "12"))).containsExactly("resync"); // 16 and 17 are missing
        assertThat(sentIds(subscribe(Mode.NORMAL, null, "17"))).isEqualTo(ids(18, 22));
        broadcaster.onCommitted(committed(LongStream.rangeClosed(23, 30)
                .mapToObj(id -> stored(id, alarmEvent())).toArray(StoredEvent[]::new))); // evicts 18..20
        assertThat(sentNames(subscribe(Mode.NORMAL, null, "17"))).containsExactly("resync");
        assertThat(sentIds(subscribe(Mode.NORMAL, null, "20"))).isEqualTo(ids(21, 30));
    }

    @Test
    void aTimedOutClientIsCountedAndRemoved() {
        client.fireTimeout();

        assertThat(counter("atmsup.sse.disconnects", "reason", "timeout")).isEqualTo(1.0);
        assertThat(gauge("atmsup.sse.clients")).isZero();
        assertThat(client.completed()).isTrue();
    }

    @Test
    void queueFillIsTheFullestClient() {
        TestEmitter slow = blockedClient();
        broadcaster.onCommitted(committed(stored(1101, alarmEvent())));
        await().atMost(ofSeconds(1)).until(slow::blockedInSend);
        broadcaster.onCommitted(committed(LongStream.rangeClosed(1102, 1165)
                .mapToObj(id -> stored(id, alarmEvent())).toArray(StoredEvent[]::new)));
        await().atMost(ofSeconds(1)).until(() -> client.ids().contains(1165L));

        assertThat(gauge("atmsup.sse.queue.fill.max")).isEqualTo(64 / 256.0);
        assertThat(gauge("atmsup.sse.clients")).isEqualTo(2.0);
    }

    @Test
    void emittersTimeOutAfterThirtyMinutes() {
        SseBroadcaster production = new SseBroadcaster(mapper, writer, InstantSource.fixed(T0),
                new SimpleMeterRegistry(), 1000, 256);
        try {
            assertThat(production.subscribe(null, null).getTimeout()).isEqualTo(Duration.ofMinutes(30).toMillis());
        } finally {
            production.closeAll();
        }
    }

    @Test
    void closingCompletesEveryClientAndLaterSubscribersAtOnce() {
        broadcaster.closeAll();

        await().atMost(ofSeconds(1)).until(client::completed);
        TestEmitter late = normalClient();
        await().atMost(ofSeconds(1)).until(late::completed);
        assertThat(gauge("atmsup.sse.clients")).isZero();
    }
}
