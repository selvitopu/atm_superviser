package io.github.selvitopu.atmsup.supervisor.history;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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
     * and selects only {@code max(id)}. {@code type} is not in {@code ix_event_log_equipment} (equipment_id,
     * occurred_at, and the primary key id the index carries), so the inner query needs row lookups to check the type,
     * and its cost grows with the log until retention or a covering index exists (v0.3). The outer query then fetches
     * one row per device by primary key.
     */
    @Query("""
            select e from EventLogEntity e where e.id in (
                select max(x.id) from EventLogEntity x
                where x.equipmentId is not null and x.type in :types group by x.equipmentId)""")
    List<EventLogEntity> findNewestPerEquipment(@Param("types") List<String> types);

    /** The events after {@code afterId} that occurred at or before {@code at}, except the given types, in id order. */
    @Query("""
            select e from EventLogEntity e
            where e.id > :afterId and e.occurredAt <= :at and e.type not in :except
            order by e.id""")
    List<EventLogEntity> findToFold(@Param("afterId") long afterId, @Param("at") Instant at,
                                    @Param("except") Collection<String> except);

    /** The newest event at or before {@code at} whose type is not {@code type}: by occurrence time, then id. */
    Optional<EventLogEntity> findFirstByTypeNotAndOccurredAtLessThanEqualOrderByOccurredAtDescIdDesc(String type,
                                                                                                      Instant at);

    /** The newest event of {@code type} at or before {@code at}: by occurrence time, then id. */
    Optional<EventLogEntity> findFirstByTypeAndOccurredAtLessThanEqualOrderByOccurredAtDescIdDesc(String type,
                                                                                                   Instant at);

    /**
     * The newest row of {@code type} per equipment id with an id above {@code afterId} that occurred in
     * [{@code from}, {@code at}], in id order. The time range keeps the inner query on {@code ix_event_log_type}
     * (type, occurred_at); like {@link #findNewestPerEquipment}, its cost grows with the rows in that range.
     */
    @Query("""
            select e from EventLogEntity e where e.id in (
                select max(x.id) from EventLogEntity x
                where x.type = :type and x.id > :afterId and x.occurredAt >= :from and x.occurredAt <= :at
                group by x.equipmentId)
            order by e.id""")
    List<EventLogEntity> findNewestOfTypePerEquipment(@Param("type") String type, @Param("afterId") long afterId,
                                                      @Param("from") Instant from, @Param("at") Instant at);
}
