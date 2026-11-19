package io.github.selvitopu.atmsup.supervisor.api;

import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.ImpactDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.OutOfServiceDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.PlanDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.ServiceChangeDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.SinglePointDto;
import io.github.selvitopu.atmsup.supervisor.board.BoardWriter;
import io.github.selvitopu.atmsup.supervisor.board.ServiceStatus;
import io.github.selvitopu.atmsup.supervisor.impact.ImpactAnalyzer;
import io.github.selvitopu.atmsup.supervisor.impact.ImpactReport;
import io.github.selvitopu.atmsup.supervisor.impact.ServiceChange;
import java.time.InstantSource;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** What-if maintenance analysis over the live board. Read-only; any signed-in role may ask. */
@RestController
@RequestMapping("/api/v1")
public class ImpactController {

    static final String BASIS = "Derived from the supervised equipment and the service rules; not a safety assessment";

    private final BoardWriter writer;
    private final ImpactAnalyzer analyzer;
    private final Inventory inventory;
    private final InstantSource clock;

    public ImpactController(BoardWriter writer, ImpactAnalyzer analyzer, Inventory inventory, InstantSource clock) {
        this.writer = writer;
        this.analyzer = analyzer;
        this.inventory = inventory;
        this.clock = clock;
    }

    @GetMapping("/impact")
    public ImpactDto impact(@RequestParam(name = "maintenance", required = false) List<String> maintenance) {
        Set<String> plan = new LinkedHashSet<>();
        for (String id : maintenance == null ? List.<String>of() : maintenance) {
            if (inventory.find(id).isEmpty()) {
                throw new ErrorResponseException(HttpStatus.BAD_REQUEST,
                        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Unknown unit: " + id), null);
            }
            plan.add(id);
        }
        return dto(analyzer.analyze(writer.current().board(), plan));
    }

    private ImpactDto dto(ImpactReport r) {
        PlanDto plan = new PlanDto(r.plan().units(), r.plan().verdict().name(), r.plan().unconfirmed(),
                changes(r.plan().services()), r.plan().notes());
        List<SinglePointDto> points = r.singlePoints().stream()
                .map(p -> new SinglePointDto(p.unit(), p.health().name(), p.verdict().name(), p.unconfirmed(),
                        changes(p.changes())))
                .toList();
        List<OutOfServiceDto> out = r.outOfService().stream()
                .map(o -> new OutOfServiceDto(o.unit(), o.reason().name())).toList();
        return new ImpactDto(clock.instant(), BASIS, plan, points, out, r.unknownUnits());
    }

    private static List<ServiceChangeDto> changes(List<ServiceChange> changes) {
        return changes.stream()
                .map(c -> new ServiceChangeDto(c.id().name(), c.id().label(), c.verdict().name(), name(c.before()),
                        name(c.beforeBestCase()), name(c.after()), name(c.afterBestCase()), c.redundancyLostAfter(),
                        c.reasonsAfter()))
                .toList();
    }

    private static String name(ServiceStatus status) {
        return status == null ? null : status.name();
    }
}
