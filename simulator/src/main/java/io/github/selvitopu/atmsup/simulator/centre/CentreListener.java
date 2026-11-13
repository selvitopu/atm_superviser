package io.github.selvitopu.atmsup.simulator.centre;

import java.util.List;

/** Receives the events of one centre mutation, in order, after the centre has released its lock. */
public interface CentreListener {

    void on(List<CentreEvent> events);
}
