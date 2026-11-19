package io.github.selvitopu.atmsup.supervisor.impact;

/** Outcome of a what-if, in severity order: the worst is the maximum by ordinal. */
public enum Verdict {
    NO_SERVICE_IMPACT, REDUCED_MARGIN, SERVICE_LOSS
}
