package io.github.selvitopu.atmsup.supervisor.impact;

import io.github.selvitopu.atmsup.supervisor.board.Health;
import java.util.List;

/** What losing this one unit, on top of the plan, would do; {@code changes} holds only the affected services. */
public record SinglePoint(String unit, Health health, Verdict verdict, boolean unconfirmed,
                          List<ServiceChange> changes) {

    public SinglePoint {
        changes = List.copyOf(changes);
    }
}
