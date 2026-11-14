package io.github.selvitopu.atmsup.supervisor.polling;

import io.github.selvitopu.atmsup.supervisor.board.BoardInput.TrapReceived;
import io.github.selvitopu.atmsup.supervisor.board.BoardInputSink;
import io.github.selvitopu.atmsup.supervisor.snmp.Notification;
import org.springframework.context.event.EventListener;

/**
 * Hands received notifications to the board writer. It runs on SNMP4J's single listener thread, which also delivers
 * poll responses, so it only submits and never waits for the result.
 */
public final class TrapForwarder {

    private final BoardInputSink sink;

    public TrapForwarder(BoardInputSink sink) {
        this.sink = sink;
    }

    @EventListener
    public void on(Notification n) {
        sink.submit(new TrapReceived(n));
    }
}
