package io.github.selvitopu.atmsup.supervisor.board;

import io.github.selvitopu.atmsup.inventory.Inventory;
import java.util.function.Predicate;

public interface ServiceRule {

    ServiceId id();

    RuleOutcome evaluate(Inventory inv, Predicate<String> available);
}
