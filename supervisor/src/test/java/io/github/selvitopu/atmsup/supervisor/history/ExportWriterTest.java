package io.github.selvitopu.atmsup.supervisor.history;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.selvitopu.atmsup.supervisor.history.ExportWriter.Document;
import io.github.selvitopu.atmsup.supervisor.history.ExportWriter.Row;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ExportWriterTest {

    private static final Instant FROM = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant TO = Instant.parse("2026-01-01T12:30:00Z");

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ExportWriter writer = new ExportWriter(mapper);

    private static IncidentSummary summary() {
        return new IncidentSummary(Map.of("CRITICAL", 2, "MAJOR", 0), 1,
                List.of(new IncidentSummary.Cause("c1", "RAD-N", "CRITICAL", "Radar failure", 4)),
                List.of(new IncidentSummary.Degradation("SURV", "Surveillance", FROM, null, "FAILED", 7200, false)),
                List.of(new IncidentSummary.CommandRow(FROM, "RAD-N", "RESTART", "supervisor1", "DONE", "c-1")),
                Map.of("DONE", 1),
                List.of(new IncidentSummary.Flood(FROM, TO, true)),
                List.of(new IncidentSummary.ContactLoss("FDPS-B", FROM, null, false)),
                List.of(new Timeline.Span(FROM, TO)), List.of(TO), List.of(), List.of());
    }

    private static Document document(List<Row> events) {
        return new Document("Test Centre", FROM, TO, Instant.parse("2026-01-02T00:00:00Z"), "supervisor1", summary(),
                events);
    }

    @Test
    void csvEscapesQuotesCommasAndNewlines() {
        Row tricky = new Row(7, FROM, "ALARM_RAISED", "RAD-N", "o\"p", "Alarm, \"raised\"\nsecond line");
        Row plain = new Row(8, FROM.plusSeconds(1), "SUPERVISOR_HEARTBEAT", null, null, "Supervisor heartbeat");

        String csv = writer.csv(document(List.of(tricky, plain)));

        assertThat(csv).contains("7,2026-01-01T10:00:00Z,ALARM_RAISED,RAD-N,\"o\"\"p\",\"Alarm, \"\"raised\"\"\n"
                + "second line\"\r\n");
        assertThat(csv).endsWith("8,2026-01-01T10:00:01Z,SUPERVISOR_HEARTBEAT,,,Supervisor heartbeat\r\n");
    }

    @Test
    void csvStartsWithTheSummaryLines() {
        String csv = writer.csv(document(List.of(new Row(1, FROM, "SUPERVISOR_STARTED", null, null, "Supervisor started"))));

        String[] lines = csv.split("\r\n");
        assertThat(lines[0]).isEqualTo("# centre: Test Centre");
        assertThat(csv).contains("# from: 2026-01-01T10:00:00Z\r\n").contains("# to: 2026-01-01T12:30:00Z\r\n")
                .contains("# generated_by: supervisor1\r\n")
                .contains("# alarms_critical: 2\r\n").contains("# alarms_suppressed: 1\r\n")
                .contains("# cause: RAD-N 101")
                .contains("# degradation: ").contains("# command: ").contains("# flood: ")
                .contains("# contact_loss: ").contains("# gap: ").contains("# restart: 2026-01-01T12:30:00Z");
        int header = java.util.Arrays.asList(lines).indexOf("id,occurred_at,type,equipment,actor,summary");
        assertThat(header).isPositive();
        assertThat(java.util.Arrays.asList(lines).subList(0, header)).allMatch(l -> l.startsWith("#") || l.isEmpty());
        assertThat(lines[header + 1]).isEqualTo("1,2026-01-01T10:00:00Z,SUPERVISOR_STARTED,,,Supervisor started");
    }

    @Test
    void jsonHasTheDocumentedShape() {
        JsonNode json = mapper.readTree(writer.json(document(List.of(
                new Row(1, FROM, "ALARM_RAISED", "RAD-N", null, "Alarm raised")))));

        assertThat(json.propertyNames()).containsExactly("centre", "from", "to", "generatedAt", "generatedBy",
                "summary", "events");
        assertThat(json.get("centre").asString()).isEqualTo("Test Centre");
        assertThat(json.get("from").asString()).isEqualTo("2026-01-01T10:00:00Z");
        assertThat(json.get("generatedBy").asString()).isEqualTo("supervisor1");
        assertThat(json.get("summary").get("alarmsBySeverity").get("CRITICAL").asInt()).isEqualTo(2);
        assertThat(json.get("summary").get("causes").get(0).get("correlated").asInt()).isEqualTo(4);
        assertThat(json.get("summary").get("degradations").get(0).get("end").isNull()).isTrue();
        assertThat(json.get("events").get(0).propertyNames()).containsExactly("id", "occurredAt", "type",
                "equipmentId", "actor", "summary");
        assertThat(json.get("events").get(0).get("occurredAt").asString()).isEqualTo("2026-01-01T10:00:00Z");
    }

    @Test
    void theFileNameCarriesTheWindow() {
        assertThat(ExportWriter.fileName(FROM, TO, "csv")).isEqualTo("incident-20260101T1000Z-20260101T1230Z.csv");
    }
}
