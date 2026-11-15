package io.github.selvitopu.atmsup.supervisor.history;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventLogRepository extends JpaRepository<EventLogEntity, Long> {

    /** True once any event is stored; stops at the first row instead of counting the log. */
    boolean existsByIdIsNotNull();

    /** The page's sort decides the order; the type is an {@code EventType} name. */
    List<EventLogEntity> findByEquipmentIdAndTypeNot(String equipmentId, String type, Pageable page);

    /**
     * The newest row per equipment id among the given event types. The inner query groups by {@code equipment_id}
     * and selects only {@code max(id)}, so it walks {@code ix_event_log_equipment} (equipment_id, occurred_at, and the
     * primary key id the index carries) without reading payloads; the outer query then fetches one row per device by
     * primary key.
     */
    @Query("""
            select e from EventLogEntity e where e.id in (
                select max(x.id) from EventLogEntity x
                where x.equipmentId is not null and x.type in :types group by x.equipmentId)""")
    List<EventLogEntity> findNewestPerEquipment(@Param("types") List<String> types);
}
