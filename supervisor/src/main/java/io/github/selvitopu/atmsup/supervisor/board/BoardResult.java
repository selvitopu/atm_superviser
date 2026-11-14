package io.github.selvitopu.atmsup.supervisor.board;

import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import java.util.List;

/**
 * The outcome of one board input. {@code committed} is false only when the input's events could not be stored;
 * {@code error} then says why. {@code acknowledged} and {@code notFound} are set for an acknowledgement.
 */
public record BoardResult(boolean committed, List<Alarm> acknowledged, List<String> notFound, String error) {

    public BoardResult {
        acknowledged = List.copyOf(acknowledged);
        notFound = List.copyOf(notFound);
    }

    public static BoardResult ok() {
        return new BoardResult(true, List.of(), List.of(), null);
    }

    public static BoardResult failed(String error) {
        return new BoardResult(false, List.of(), List.of(), error);
    }
}
