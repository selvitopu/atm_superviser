package io.github.selvitopu.atmsup.supervisor.api;

import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.BoardDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.EquipmentDetailDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.EquipmentDto;
import io.github.selvitopu.atmsup.supervisor.api.Dtos.EventSummaryDto;
import io.github.selvitopu.atmsup.supervisor.board.BoardWriter;
import io.github.selvitopu.atmsup.supervisor.board.LiveBoard;
import io.github.selvitopu.atmsup.supervisor.history.EventQueries;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class BoardController {

    static final int RECENT_EVENTS = 50;

    private final BoardWriter writer;
    private final EventQueries queries;
    private final DtoMapper mapper;
    private final Inventory inventory;

    public BoardController(BoardWriter writer, EventQueries queries, DtoMapper mapper, Inventory inventory) {
        this.writer = writer;
        this.queries = queries;
        this.mapper = mapper;
        this.inventory = inventory;
    }

    @GetMapping("/board")
    public BoardDto board() {
        return mapper.board(writer.current(), inventory.centre(), Instant.now());
    }

    @GetMapping("/equipment/{id}")
    public EquipmentDetailDto equipment(@PathVariable String id) {
        LiveBoard lb = writer.current();
        EquipmentDto equipment = mapper.equipment(lb, id); // 404 for an unknown id, before any query
        List<EventSummaryDto> events = queries.recentForEquipment(id, RECENT_EVENTS).stream()
                .map(e -> new EventSummaryDto(e.id(), e.event().occurredAt(), e.event().type().name(),
                        EventSummaries.describe(e)))
                .toList();
        return new EquipmentDetailDto(equipment, events);
    }
}
