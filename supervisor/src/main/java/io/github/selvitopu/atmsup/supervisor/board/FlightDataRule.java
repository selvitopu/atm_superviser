package io.github.selvitopu.atmsup.supervisor.board;

import io.github.selvitopu.atmsup.inventory.DeviceType;
import io.github.selvitopu.atmsup.inventory.Inventory;
import java.util.List;
import java.util.function.Predicate;

public final class FlightDataRule implements ServiceRule {

    @Override
    public ServiceId id() {
        return ServiceId.FD;
    }

    @Override
    public RuleOutcome evaluate(Inventory inv, Predicate<String> available) {
        long fdps = inv.byType(DeviceType.FDPS).stream().filter(d -> available.test(d.id())).count();
        List<String> failed = fdps == 0 ? List.of("No FDPS unit available") : List.of();
        List<String> degraded = fdps == 1 ? List.of("Only one FDPS unit available") : List.of();
        return Outcomes.of(failed, degraded, fdps < 2);
    }
}
