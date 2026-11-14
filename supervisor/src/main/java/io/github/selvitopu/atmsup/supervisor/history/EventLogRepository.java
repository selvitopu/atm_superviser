package io.github.selvitopu.atmsup.supervisor.history;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EventLogRepository extends JpaRepository<EventLogEntity, Long> {

    /** True once any event is stored; stops at the first row instead of counting the log. */
    boolean existsByIdIsNotNull();
}
