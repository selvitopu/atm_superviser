package io.github.selvitopu.atmsup.supervisor.history;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BoardSnapshotRepository extends JpaRepository<BoardSnapshotEntity, Long> {

    /** The newest snapshot taken at or before the instant; the later of two taken together wins. */
    Optional<BoardSnapshotEntity> findFirstByTakenAtLessThanEqualOrderByTakenAtDescIdDesc(Instant at);
}
