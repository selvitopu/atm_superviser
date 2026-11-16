package io.github.selvitopu.atmsup.supervisor.api;

import io.github.selvitopu.atmsup.supervisor.api.Dtos.CommandRequest;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.CommandResultDto;
import io.github.selvitopu.atmsup.supervisor.control.CommandOutcome;
import io.github.selvitopu.atmsup.supervisor.control.EquipmentControl;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Equipment control for supervisors: maintenance mode, restart and make active. */
@RestController
@RequestMapping("/api/v1")
public class CommandController {

    static final String REFUSED = "The equipment refused the command: restart needs maintenance mode, and make active"
            + " needs a standby unit";

    private final EquipmentControl control;

    public CommandController(EquipmentControl control) {
        this.control = control;
    }

    @PostMapping("/equipment/{id}/commands")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public ResponseEntity<?> command(@PathVariable String id, @RequestBody @Valid CommandRequest body,
                                     Principal principal) {
        CommandOutcome outcome = control.command(id, body.command(), principal.getName());
        return switch (outcome) {
            case DONE -> ResponseEntity.ok(new CommandResultDto(id, body.command().name(), outcome.name()));
            case REFUSED -> problem(HttpStatus.CONFLICT, REFUSED);
            case NO_ANSWER -> problem(HttpStatus.GATEWAY_TIMEOUT, "No answer from the equipment");
            case FAILED -> problem(HttpStatus.BAD_GATEWAY,
                    "The command could not be sent to the equipment; check the operator SNMP credentials");
        };
    }

    private static ResponseEntity<?> problem(HttpStatus status, String detail) {
        return ResponseEntity.of(ProblemDetail.forStatusAndDetail(status, detail)).build();
    }
}
