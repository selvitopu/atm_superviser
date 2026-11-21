package io.github.selvitopu.atmsup.supervisor.history;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Writes an incident report as JSON or as CSV: the summary first, then one row per stored event. */
@Component
public class ExportWriter {

    private static final DateTimeFormatter NAME_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm'Z'")
            .withZone(ZoneOffset.UTC);
    private static final String EOL = "\r\n";

    private final JsonMapper mapper;

    public ExportWriter(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /** One event of the window; samples are never exported. */
    public record Row(long id, Instant occurredAt, String type, String equipmentId, String actor, String summary) {
    }

    /** The whole report; the JSON is this record. */
    public record Document(String centre, Instant from, Instant to, Instant generatedAt, String generatedBy,
                           IncidentSummary summary, List<Row> events) {
    }

    /** {@code incident-<from>-<to>.<extension>}, the times as {@code yyyyMMdd'T'HHmm'Z'} in UTC. */
    public static String fileName(Instant from, Instant to, String extension) {
        return "incident-" + NAME_TIME.format(from) + "-" + NAME_TIME.format(to) + "." + extension;
    }

    public String json(Document document) {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(document);
    }

    /** {@code # key: value} lines, then the header and one RFC 4180 record per event (lines end in CRLF). */
    public String csv(Document d) {
        StringBuilder out = new StringBuilder();
        comment(out, "centre", d.centre());
        comment(out, "from", d.from());
        comment(out, "to", d.to());
        comment(out, "generated_at", d.generatedAt());
        comment(out, "generated_by", d.generatedBy());
        IncidentSummary s = d.summary();
        s.alarmsBySeverity().forEach((severity, count) -> comment(out, "alarms_" + severity.toLowerCase(), count));
        comment(out, "alarms_suppressed", s.suppressedAlarms());
        s.causes().forEach(c -> comment(out, "cause", c.equipmentId() + " 101 " + c.text() + " [" + c.severity()
                + "] alarm " + c.alarmId() + ", correlated alarms " + c.correlated()));
        s.degradations().forEach(g -> comment(out, "degradation", g.service() + " from " + (g.startedBeforeFrom() ? "before " : "") + g.start() + " to "
                + (g.end() == null ? "still" : g.end()) + ", worst " + g.worst() + ", " + g.durationSeconds() + " s"
                + (g.endedByRestart() ? ", ended by supervisor restart" : "")));
        s.commands().forEach(c -> comment(out, "command", c.at() + " " + c.equipmentId() + " " + c.command() + " by "
                + c.actor() + ": " + c.outcome() + (c.commandId() == null ? "" : " (" + c.commandId() + ")")));
        s.commandCounts().forEach((outcome, count) -> comment(out, "commands_" + outcome.toLowerCase(), count));
        s.floods().forEach(f -> comment(out, "flood", f.start() + " to " + (f.end() == null ? "still" : f.end())
                + (f.endedByRestart() ? ", ended by supervisor restart" : "")));
        s.contactLosses().forEach(c -> comment(out, "contact_loss", c.equipmentId() + " lost " + c.lostAt()
                + ", restored " + (c.restoredAt() == null ? "still lost" : c.restoredAt())
                + (c.endedByRestart() ? ", ended by supervisor restart" : "")));
        s.gaps().forEach(g -> comment(out, "gap", g.start() + " to " + (g.end() == null ? "still" : g.end())
                + ": no recorded data, supervisor not running"));
        s.restarts().forEach(r -> comment(out, "restart", r));
        s.openUnacknowledged().forEach(a -> comment(out, "open_unacknowledged", openAlarm(a)));
        s.openAcknowledged().forEach(a -> comment(out, "open_acknowledged", openAlarm(a)));
        out.append("id,occurred_at,type,equipment,actor,summary").append(EOL);
        for (Row r : d.events()) {
            out.append(r.id()).append(',').append(r.occurredAt()).append(',').append(field(r.type())).append(',')
                    .append(field(r.equipmentId())).append(',').append(field(r.actor())).append(',')
                    .append(field(r.summary())).append(EOL);
        }
        return out.toString();
    }

    private static String openAlarm(IncidentSummary.OpenAlarm a) {
        return a.equipmentId() + " " + a.code() + " " + a.text() + " [" + a.severity() + "] raised " + a.raisedAt();
    }

    /** One summary line; a value's line breaks become spaces, so that a comment is always one line. */
    private static void comment(StringBuilder out, String key, Object value) {
        out.append("# ").append(key).append(": ").append(String.valueOf(value).replaceAll("[\\r\\n]+", " "))
                .append(EOL);
    }

    /** RFC 4180: a field with a comma, a quote or a line break is quoted, and its quotes are doubled. */
    private static String field(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
