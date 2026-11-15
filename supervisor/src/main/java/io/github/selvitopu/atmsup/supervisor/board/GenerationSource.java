package io.github.selvitopu.atmsup.supervisor.board;

/** The current per-device generations; a probe or poll reads one when it starts and carries it in its result. */
public interface GenerationSource {

    /** The status generation (traps and contact changes): read by a full poll. */
    long generation(String equipmentId);

    /** The contact generation (contact changes only): read by a probe. */
    long contactGeneration(String equipmentId);
}
