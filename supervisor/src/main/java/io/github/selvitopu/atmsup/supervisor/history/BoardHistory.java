package io.github.selvitopu.atmsup.supervisor.history;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.supervisor.board.Board;
import io.github.selvitopu.atmsup.supervisor.board.BoardReducer;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.TelemetrySampleEvent;
import io.github.selvitopu.atmsup.supervisor.board.EquipmentState;
import io.github.selvitopu.atmsup.supervisor.board.EventType;
import io.github.selvitopu.atmsup.supervisor.board.Snapshot;
import io.github.selvitopu.atmsup.supervisor.board.SnapshotStore;
import io.github.selvitopu.atmsup.supervisor.board.StoredEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The board at any instant, rebuilt from the stored log: the newest snapshot at or before it, plus a fold of every
 * later event up to it with {@link BoardReducer}, the same fold the live board is (ADR-0004). An instant after now is
 * answered for now.
 */
@Component
public class BoardHistory {

    private static final String SAMPLED = EventType.TELEMETRY_SAMPLED.name();
    private static final String STARTED = EventType.SUPERVISOR_STARTED.name();
    /** Not read for the fold: the reducer leaves the board as it is for both, and samples are most of the log. */
    private static final List<String> NOT_FOLDED = List.of(SAMPLED, EventType.EQUIPMENT_COMMANDED.name());

    private final Inventory inventory;
    private final SnapshotStore snapshots;
    private final EventLogRepository events;
    private final EventCodec codec;
    private final InstantSource clock;
    private final GapRule gapRule;
    private final Set<String> units;

    public BoardHistory(Inventory inventory, SnapshotStore snapshots, EventLogRepository events, EventCodec codec,
                        InstantSource clock,
                        @Value("${atm.supervisor.heartbeat-interval:60s}") Duration heartbeatInterval) {
        this.inventory = inventory;
        this.snapshots = snapshots;
        this.events = events;
        this.codec = codec;
        this.clock = clock;
        this.gapRule = new GapRule(heartbeatInterval.multipliedBy(3).dividedBy(2));
        this.units = inventory.devices().stream().map(Device::id).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The durable board at {@code at}, each unit's newest sample at or before it (only samples after the newest
     * SUPERVISOR_STARTED at or before it count, as live drops telemetry at a restart) and the gap flag.
     */
    @Transactional(readOnly = true)
    public ReplayBoard boardAt(Instant at) {
        Instant t = notAfterNow(at);
        Optional<EventLogEntity> newest =
                events.findFirstByTypeNotAndOccurredAtLessThanEqualOrderByOccurredAtDescIdDesc(SAMPLED, t);
        boolean noData = gapRule.noDataAt(t, newest.map(EventLogEntity::getOccurredAt).orElse(null),
                newest.map(EventLogEntity::getType).orElse(null));
        return new ReplayBoard(t, foldAt(t), samplesAt(t), noData,
                newest.map(EventLogEntity::getOccurredAt).orElse(null), newest.map(EventLogEntity::getType).orElse(null));
    }

    /**
     * The durable board only: equipment, services, open alarms and flood. Its {@code lastEventId} is the id of the
     * newest event at or before {@code at}, samples and commands included, so the events after it are exactly those
     * after the instant.
     */
    @Transactional(readOnly = true)
    public Board fold(Instant at) {
        return foldAt(notAfterNow(at));
    }

    public GapRule gapRule() {
        return gapRule;
    }

    /**
     * From the newest snapshot, or from the initial board when there is none (every unit UNKNOWN, no alarms). Only
     * the units of the current inventory are kept, as the API looks every unit up there.
     */
    private Board foldAt(Instant at) {
        Board board = snapshots.latestAtOrBefore(at).map(Snapshot::board).map(this::withEveryUnit)
                .orElseGet(() -> Board.initial(inventory, List.of()));
        for (EventLogEntity row : events.findToFold(board.lastEventId(), at, NOT_FOLDED)) {
            board = BoardReducer.apply(board, new StoredEvent(row.getId(), codec.decode(row)));
        }
        long newest = events.findFirstByOccurredAtLessThanEqualOrderByOccurredAtDescIdDesc(at)
                .map(EventLogEntity::getId).orElse(0L);
        Map<String, EquipmentState> equipment = new LinkedHashMap<>(board.equipment());
        equipment.keySet().retainAll(units);
        // never below the last event folded, should the clock have stepped back between two commits
        return new Board(equipment, board.services(), board.openAlarms(), Math.max(board.lastEventId(), newest),
                board.flood());
    }

    /**
     * A unit added to the inventory after the snapshot was taken starts as on the initial board, so the writer
     * always has a state for every unit it polls; the units the inventory no longer has are dropped after the fold.
     */
    private Board withEveryUnit(Board snapshot) {
        Map<String, EquipmentState> initial = Board.initial(inventory, List.of()).equipment();
        if (snapshot.equipment().keySet().containsAll(initial.keySet())) {
            return snapshot;
        }
        Map<String, EquipmentState> equipment = new LinkedHashMap<>(snapshot.equipment());
        initial.forEach(equipment::putIfAbsent);
        return new Board(equipment, snapshot.services(), snapshot.openAlarms(), snapshot.lastEventId(),
                snapshot.flood());
    }

    /** With no SUPERVISOR_STARTED at or before {@code at}, every sample up to it counts. */
    private Map<String, Sample> samplesAt(Instant at) {
        Optional<EventLogEntity> started =
                events.findFirstByTypeAndOccurredAtLessThanEqualOrderByOccurredAtDescIdDesc(STARTED, at);
        long afterId = started.map(EventLogEntity::getId).orElse(0L);
        Instant from = started.map(EventLogEntity::getOccurredAt).orElse(Instant.EPOCH);
        Map<String, Sample> samples = new LinkedHashMap<>();
        for (EventLogEntity row : events.findNewestOfTypePerEquipment(SAMPLED, afterId, from, at)) {
            TelemetrySampleEvent s = (TelemetrySampleEvent) codec.decode(row);
            samples.put(s.equipmentId(), new Sample(s.equipmentId(), s.observedAt(), s.values(), s.tables()));
        }
        return samples;
    }

    private Instant notAfterNow(Instant at) {
        Instant now = clock.instant();
        return at.isAfter(now) ? now : at;
    }
}
