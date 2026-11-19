package io.github.selvitopu.atmsup.supervisor.impact;

import io.github.selvitopu.atmsup.supervisor.board.ServiceId;
import io.github.selvitopu.atmsup.supervisor.board.ServiceStatus;
import java.util.List;

/**
 * One service's change. A side whose optimistic and pessimistic passes disagree has status UNKNOWN and the
 * optimistic status as its best case; otherwise the best case is null.
 */
public record ServiceChange(ServiceId id, Verdict verdict, ServiceStatus before, ServiceStatus beforeBestCase,
                            ServiceStatus after, ServiceStatus afterBestCase, boolean redundancyLostAfter,
                            List<String> reasonsAfter) {

    public ServiceChange {
        reasonsAfter = List.copyOf(reasonsAfter);
    }
}
