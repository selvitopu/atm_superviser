package io.github.selvitopu.atmsup.supervisor.polling;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.selvitopu.atmsup.supervisor.board.BoardInput;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.TrapReceived;
import io.github.selvitopu.atmsup.supervisor.board.BoardResult;
import io.github.selvitopu.atmsup.supervisor.snmp.Notification.StatusChangeNotification;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class TrapForwarderTest {

    @Test
    void forwardsTheNotificationWithoutWaitingForTheBoard() {
        List<BoardInput> inputs = new CopyOnWriteArrayList<>();
        CompletableFuture<BoardResult> neverProcessed = new CompletableFuture<>();
        TrapForwarder forwarder = new TrapForwarder(in -> {
            inputs.add(in);
            return neverProcessed;
        });
        StatusChangeNotification n = new StatusChangeNotification("RAD-N", Instant.parse("2026-11-14T10:00:00Z"));

        forwarder.on(n); // returns although the writer never completes the future

        assertThat(inputs).containsExactly(new TrapReceived(n));
    }
}
