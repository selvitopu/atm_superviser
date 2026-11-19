package io.github.selvitopu.atmsup.supervisor.board;

/** One rule's result in the optimistic and the pessimistic pass, for a what-if question. */
public record Assessment(RuleOutcome optimistic, RuleOutcome pessimistic) {

    /** True when both passes agree on the status, so UNKNOWN equipment does not change the answer. */
    public boolean confirmed() {
        return optimistic.status() == pessimistic.status();
    }
}
