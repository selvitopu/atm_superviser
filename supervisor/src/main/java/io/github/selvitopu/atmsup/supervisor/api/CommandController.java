package io.github.selvitopu.atmsup.supervisor.api;

import io.github.selvitopu.atmsup.supervisor.api.Dtos.CommandRequest;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.CommandResultDto;
import io.github.selvitopu.atmsup.supervisor.control.CommandResult;
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

/**
 * Equipment control for supervisors: maintenance mode, restart and make active. Every outcome but DONE is a problem
 * detail carrying the reason {@link EquipmentControl} gives.
 */
@RestController
@RequestMapping("/api/v1")
public class CommandController {

    private final EquipmentControl control;

    public CommandController(EquipmentControl control) {
        this.control = control;
    }

    @PostMapping("/equipment/{id}/commands")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public ResponseEntity<?> command(@PathVariable String id, @RequestBody @Valid CommandRequest body,
                                     Principal principal) {
        CommandResult result = control.command(id, body.command(), principal.getName());
        return switch (result.outcome()) {
            case DONE -> ResponseEntity.ok(new CommandResultDto(id, body.command().name(), result.outcome().name()));
            case REFUSED -> problem(HttpStatus.CONFLICT, result.reason());
            case NO_ANSWER -> problem(HttpStatus.GATEWAY_TIMEOUT, result.reason());
            case FAILED -> problem(HttpStatus.BAD_GATEWAY, result.reason());
            case NOT_SENT -> problem(HttpStatus.SERVICE_UNAVAILABLE, result.reason());
            case ISSUED -> throw new IllegalStateException("A command is answered with its outcome, never as issued");
        };
    }

    private static ResponseEntity<?> problem(HttpStatus status, String detail) {
        return ResponseEntity.of(ProblemDetail.forStatusAndDetail(status, detail)).build();
    }
}
