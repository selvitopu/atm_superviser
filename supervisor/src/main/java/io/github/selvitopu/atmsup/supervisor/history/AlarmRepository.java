package io.github.selvitopu.atmsup.supervisor.history;

import io.github.selvitopu.atmsup.supervisor.alarms.AlarmState;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlarmRepository extends JpaRepository<AlarmEntity, String> {

    List<AlarmEntity> findByStateNotOrderByRaisedAtAscIdAsc(AlarmState state);
}
