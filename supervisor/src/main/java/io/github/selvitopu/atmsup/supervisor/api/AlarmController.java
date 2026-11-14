package io.github.selvitopu.atmsup.supervisor.api;

import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.AckManyRequest;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.AckRequest;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.AckResultDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.AlarmDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.PageDto;
import io.github.selvitopu.atmsup.supervisor.board.Board;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.AcknowledgeAlarms;
import io.github.selvitopu.atmsup.supervisor.board.BoardResult;
import io.github.selvitopu.atmsup.supervisor.board.BoardWriter;
import io.github.selvitopu.atmsup.supervisor.history.EventQueries;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/alarms")
public class AlarmController {

    /** Until v0.3 adds security, every acknowledgement is made by this actor. */
    static final String ACTOR = "demo-user";
    static final int MAX_PAGE_SIZE = 500;
    private static final Logger log = LoggerFactory.getLogger(AlarmController.class);

    private final BoardWriter writer;
    private final EventQueries queries;
    private final DtoMapper mapper;
    private final Duration submitTimeout;

    public AlarmController(BoardWriter writer, EventQueries queries, DtoMapper mapper,
                           @Value("${atm.supervisor.api.submit-timeout:10s}") Duration submitTimeout) {
        this.writer = writer;
        this.queries = queries;
        this.mapper = mapper;
        this.submitTimeout = submitTimeout;
    }

    /** {@code state} is {@code open} (every alarm that is not CLOSED) or {@code all}. */
    @GetMapping
    public PageDto<AlarmDto> search(
            @RequestParam(defaultValue = "open") @Pattern(regexp = "open|all") String state,
            @RequestParam(required = false) Severity severity,
            @RequestParam(required = false) String equipment,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        Page<Alarm> result = queries.searchAlarms(state, severity, equipment, from, to, PageRequest.of(page, size));
        Board board = writer.current().board();
        List<AlarmDto> items = result.getContent().stream().map(a -> mapper.alarm(a, board)).toList();
        return new PageDto<>(items, result.getNumber(), result.getSize(), result.getTotalElements());
    }

    @PostMapping("/{id}/ack")
    public AlarmDto acknowledge(@PathVariable String id, @RequestBody(required = false) @Valid AckRequest body) {
        BoardResult result = submit(List.of(id), body == null ? null : body.comment());
        if (!result.notFound().isEmpty() || result.acknowledged().isEmpty()) {
            throw new NoSuchElementException("Unknown alarm " + id);
        }
        return mapper.alarm(result.acknowledged().getFirst(), writer.current().board());
    }

    @PostMapping("/ack")
    public AckResultDto acknowledgeMany(@RequestBody @Valid AckManyRequest body) {
        BoardResult result = submit(body.ids(), body.comment());
        Board board = writer.current().board();
        return new AckResultDto(result.acknowledged().stream().map(a -> mapper.alarm(a, board)).toList(),
                result.notFound());
    }

    private BoardResult submit(List<String> ids, String comment) {
        BoardResult result;
        try {
            // get(timeout) leaves the writer's own future untouched
            result = writer.submit(new AcknowledgeAlarms(ids, comment, ACTOR))
                    .get(submitTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("The board writer did not answer an acknowledgement within {}", submitTimeout, e);
            throw new BoardUpdateFailedException("The board did not answer in time", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for the board writer", e);
            throw new BoardUpdateFailedException("Interrupted while waiting for the board", e);
        } catch (ExecutionException | RuntimeException e) { // RuntimeException covers cancellation and a synchronous throw
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            log.error("The board writer failed an acknowledgement", cause);
            throw new BoardUpdateFailedException("The acknowledgement could not be processed; try again", cause);
        }
        if (!result.committed()) {
            throw new BoardUpdateFailedException("The acknowledgement could not be stored; try again");
        }
        return result;
    }
}
