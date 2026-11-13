package io.github.selvitopu.atmsup.supervisor.alarms;

import java.util.UUID;

public interface IdGenerator {

    String next();

    static IdGenerator uuid() {
        return () -> UUID.randomUUID().toString();
    }
}
