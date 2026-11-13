package io.github.selvitopu.atmsup.supervisor.board;

import io.github.selvitopu.atmsup.inventory.DeviceType;
import io.github.selvitopu.atmsup.inventory.Inventory;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class SurveillanceRule implements ServiceRule {

    @Override
    public ServiceId id() {
        return ServiceId.SURV;
    }

    @Override
    public RuleOutcome evaluate(Inventory inv, Predicate<String> available) {
        long sdps = count(inv, DeviceType.SDPS, available);
        long radars = count(inv, DeviceType.RADAR, available);
        boolean adsbMissing = !inv.byType(DeviceType.ADSB).isEmpty() && count(inv, DeviceType.ADSB, available) == 0;

        List<String> failed = new ArrayList<>();
        List<String> degraded = new ArrayList<>();
        if (sdps == 0) {
            failed.add("No SDPS unit available");
        } else if (sdps == 1) {
            degraded.add("Only one SDPS unit available");
        }
        if (radars == 0) {
            failed.add("No radar available");
        } else if (radars < 2) {
            degraded.add("Fewer than 2 radars available");
        }
        if (adsbMissing) {
            degraded.add("ADS-B unavailable");
        }
        return Outcomes.of(failed, degraded, sdps < 2);
    }

    private static long count(Inventory inv, DeviceType type, Predicate<String> available) {
        return inv.byType(type).stream().filter(d -> available.test(d.id())).count();
    }
}
