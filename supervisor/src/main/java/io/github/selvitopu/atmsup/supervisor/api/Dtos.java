package io.github.selvitopu.atmsup.supervisor.api;

import io.github.selvitopu.atmsup.supervisor.control.EquipmentCommand;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The JSON shapes of the REST API. Times are UTC instants serialised as ISO-8601 strings. */
public final class Dtos {

    private Dtos() {
    }

    public record BoardDto(String centre, Instant serverTime, long lastEventId, List<ServiceDto> services,
                           List<EquipmentDto> equipment, List<AlarmDto> alarms, FloodDto flood) {
    }

    /** Whether an alarm flood is in progress; {@code since} is null when it is not. */
    public record FloodDto(boolean active, Instant since) {
    }

    public record ServiceDto(String id, String name, String status, String bestCase, boolean redundancyLost,
                             List<String> reasons, Instant since) {
    }

    public record EquipmentDto(String id, String name, String type, String group, String pair, String health,
                               String unknownReason, boolean maintenance, String role, Instant healthSince,
                               ContactDto contact, Map<String, Object> values,
                               Map<String, List<Map<String, Object>>> tables) {
    }

    public record ContactDto(boolean reachable, Instant lastContactAt, Instant lostAt, Instant lastPollAt,
                             int consecutiveFailures, long pollIntervalMs) {
    }

    /** {@code unconfirmed} is true when the alarm's equipment is UNKNOWN: the alarm may be stale. */
    public record AlarmDto(String id, String equipmentId, int code, int instance, String instanceName,
                           String severity, String text, String source, String state, boolean suppressed,
                           boolean unconfirmed, Instant raisedAt, Instant ackedAt, String ackedBy,
                           String ackComment, Instant clearedAt, CorrelationDto correlation) {
    }

    /** The cause an alarm is correlated to, as stored when the link was made. */
    public record CorrelationDto(String causeId, String causeEquipmentId, int causeCode, String rule) {
    }

    public record EquipmentDetailDto(EquipmentDto equipment, List<EventSummaryDto> recentEvents) {
    }

    public record EventSummaryDto(long id, Instant occurredAt, String type, String summary) {
    }

    public record PageDto<T>(List<T> items, int page, int size, long total) {
    }

    public record AckRequest(@Size(max = 500) String comment) {
    }

    public record AckManyRequest(@NotEmpty @Size(min = 1, max = 500) List<String> ids,
                                 @Size(max = 500) String comment) {
    }

    public record AckResultDto(List<AlarmDto> acknowledged, List<String> notFound) {
    }

    /** SSE {@code telemetry}: live measurements of one device after a successful poll. */
    public record TelemetryDto(String equipmentId, Instant lastPollAt, int consecutiveFailures,
                               Map<String, Object> values, Map<String, List<Map<String, Object>>> tables,
                               Instant serverTime) {
    }

    /** SSE {@code heartbeat}. */
    public record HeartbeatDto(Instant serverTime) {
    }

    /** SSE {@code supervisor}: a supervisor event (started, heartbeat, stopping). */
    public record SupervisorEventDto(String type, Instant occurredAt) {
    }

    /** A command for one unit; see {@link EquipmentCommand}. */
    public record CommandRequest(@NotNull EquipmentCommand command) {
    }

    public record CommandResultDto(String equipmentId, String command, String outcome) {
    }

    /** What-if maintenance report; {@code basis} says what the verdicts rest on. */
    public record ImpactDto(Instant asOf, String basis, PlanDto plan, List<SinglePointDto> singlePoints,
                            List<OutOfServiceDto> outOfService, List<String> unknownUnits) {
    }

    public record PlanDto(List<String> units, String verdict, boolean unconfirmed, List<ServiceChangeDto> services,
                          List<String> notes) {
    }

    /** {@code name} is the service label; the best cases are null unless the side is UNKNOWN. */
    public record ServiceChangeDto(String id, String name, String verdict, String before, String beforeBestCase,
                                   String after, String afterBestCase, boolean redundancyLostAfter,
                                   List<String> reasonsAfter) {
    }

    public record SinglePointDto(String unit, String health, String verdict, boolean unconfirmed,
                                 List<ServiceChangeDto> changes) {
    }

    public record OutOfServiceDto(String unit, String reason) {
    }
}
