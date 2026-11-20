package io.github.selvitopu.atmsup.supervisor.board;

import java.time.Instant;

/** Whether an alarm flood is in progress; {@code since} is set exactly when {@code active}. */
public record AlarmFlood(boolean active, Instant since) {

    public static final AlarmFlood NONE = new AlarmFlood(false, null);

    public AlarmFlood {
        if (active != (since != null)) {
            throw new IllegalArgumentException("since is set exactly when the flood is active");
        }
    }
}
