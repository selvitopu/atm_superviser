package io.github.selvitopu.atmsup.supervisor.board;

/** The current per-device generation; a probe or poll reads it when it starts and carries it in its result. */
public interface GenerationSource {

    long generation(String equipmentId);
}
