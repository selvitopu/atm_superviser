package io.github.selvitopu.atmsup.supervisor.history;

import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmState;
import io.github.selvitopu.atmsup.supervisor.board.EventType;
import io.github.selvitopu.atmsup.supervisor.board.StoredEvent;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Read-only queries over the event log and the alarm table for the REST API. */
@Component
public class EventQueries {

    private final EventLogRepository events;
    private final AlarmRepository alarms;
    private final EventCodec codec;

    public EventQueries(EventLogRepository events, AlarmRepository alarms, EventCodec codec) {
        this.events = events;
        this.alarms = alarms;
        this.codec = codec;
    }

    /**
     * The latest events of one device, newest first. TELEMETRY_SAMPLED is excluded so that samples, written every
     * 30 s, never push state changes out of the list.
     */
    @Transactional(readOnly = true)
    public List<StoredEvent> recentForEquipment(String equipmentId, int limit) {
        return events.findByEquipmentIdAndTypeNot(equipmentId, EventType.TELEMETRY_SAMPLED.name(),
                        PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "id")))
                .stream().map(row -> new StoredEvent(row.getId(), codec.decode(row))).toList();
    }

    /**
     * Alarms newest first (by raise time, then id). Null filters are ignored.
     *
     * @param state {@code open} for every alarm that is not CLOSED, {@code all} for every alarm
     * @param from  inclusive lower bound of {@code raisedAt}
     * @param to    exclusive upper bound of {@code raisedAt}
     */
    @Transactional(readOnly = true)
    public Page<Alarm> searchAlarms(String state, Severity severity, String equipmentId, Instant from, Instant to,
                                    Pageable page) {
        Specification<AlarmEntity> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if ("open".equals(state)) {
                p.add(cb.notEqual(root.get("state"), AlarmState.CLOSED));
            }
            if (severity != null) {
                p.add(cb.equal(root.get("severity"), severity));
            }
            if (equipmentId != null) {
                p.add(cb.equal(root.get("equipmentId"), equipmentId));
            }
            if (from != null) {
                p.add(cb.greaterThanOrEqualTo(root.<Instant>get("raisedAt"), from));
            }
            if (to != null) {
                p.add(cb.lessThan(root.<Instant>get("raisedAt"), to));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        Pageable sorted = PageRequest.of(page.getPageNumber(), page.getPageSize(),
                Sort.by(Sort.Direction.DESC, "raisedAt").and(Sort.by(Sort.Direction.DESC, "id")));
        return alarms.findAll(spec, sorted).map(AlarmEntity::toAlarm);
    }
}
