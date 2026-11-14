package io.github.selvitopu.atmsup.supervisor.history;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventLogRepository extends JpaRepository<EventLogEntity, Long> {

    /** True once any event is stored; stops at the first row instead of counting the log. */
    boolean existsByIdIsNotNull();

    /** The page's sort decides the order; the type is an {@code EventType} name. */
    List<EventLogEntity> findByEquipmentIdAndTypeNot(String equipmentId, String type, Pageable page);
}
