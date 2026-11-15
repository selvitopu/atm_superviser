package io.github.selvitopu.atmsup.supervisor.snmp;

import static io.github.selvitopu.atmsup.mib.AtmTsOids.*;
import static io.github.selvitopu.atmsup.mib.EquipmentStatus.OPERATIONAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.DeviceType;
import io.github.selvitopu.atmsup.inventory.Group;
import io.github.selvitopu.atmsup.mib.ActiveChannel;
import io.github.selvitopu.atmsup.mib.AtmTsOids;
import io.github.selvitopu.atmsup.mib.CommandResult;
import io.github.selvitopu.atmsup.mib.DateAndTimes;
import io.github.selvitopu.atmsup.mib.EquipmentStatus;
import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.mib.SensorInputStatus;
import io.github.selvitopu.atmsup.mib.Severity;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.Counter32;
import org.snmp4j.smi.Gauge32;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.Null;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;

class ObservationMapperTest {

    private static final Instant T0 = Instant.parse("2026-11-13T10:00:00Z");
    private static final Device RAD_N = device("RAD-N", DeviceType.RADAR, Group.SURVEILLANCE, null);
    private static final Device SDPS_A = device("SDPS-A", DeviceType.SDPS, Group.SURVEILLANCE, "SDPS-B");
    private static final Device FDPS_A = device("FDPS-A", DeviceType.FDPS, Group.FLIGHT_DATA, "FDPS-B");

    private final ObservationMapper mapper = new ObservationMapper();

    private static Device device(String id, DeviceType type, Group group, String pair) {
        return new Device(id, type, id, group, "127.0.0.1", 16101, pair, Duration.ofSeconds(5));
    }

    /** Equipment scalars of a healthy device; later entries replace earlier ones with the same object. */
    private static Map<OID, Variable> equipment(String id, int status, int role) {
        Map<OID, Variable> m = new LinkedHashMap<>();
        m.put(EQ_ID, new OctetString(id));
        m.put(EQ_TYPE, new Integer32(1));
        m.put(EQ_STATUS, new Integer32(status));
        m.put(EQ_ROLE, new Integer32(role));
        m.put(EQ_STATUS_CHANGED_AT, DateAndTimes.encode(Instant.parse("2026-11-13T09:00:00Z")));
        m.put(EQ_SW_VERSION, new OctetString("1.2.3"));
        m.put(EQ_MAINTENANCE_MODE, new Integer32(2));
        m.put(EQ_COMMAND, new Integer32(1));
        m.put(EQ_LAST_COMMAND_RESULT, new Integer32(CommandResult.SUCCESS.code()));
        return m;
    }

    private static Map<OID, Variable> radar() {
        Map<OID, Variable> m = equipment("RAD-N", 1, 1);
        m.put(RADAR_ROTATION_PERIOD_MS, new Gauge32(4000));
        m.put(RADAR_PLOTS_PER_SCAN, new Gauge32(300));
        m.put(RADAR_ACTIVE_CHANNEL, new Integer32(ActiveChannel.CHANNEL_A.code()));
        m.put(RADAR_CHANNEL_A_STATUS, new Integer32(1));
        m.put(RADAR_CHANNEL_B_STATUS, new Integer32(1));
        return m;
    }

    private static List<VariableBinding> bindings(Map<OID, Variable> objects) {
        List<VariableBinding> vbs = new ArrayList<>();
        objects.forEach((oid, v) -> vbs.add(new VariableBinding(scalar(oid), v)));
        return vbs;
    }

    private static Map<OID, Variable> with(Map<OID, Variable> m, OID oid, Variable v) {
        m.put(oid, v);
        return m;
    }

    private static Map<OID, Variable> without(Map<OID, Variable> m, OID oid) {
        m.remove(oid);
        return m;
    }

    private static List<VariableBinding> sensorRows() {
        // rows are served name then status per index, in OID order within a column (name column first)
        List<VariableBinding> rows = new ArrayList<>();
        String[] names = {"RAD-N", "RAD-E", "RAD-S"};
        int[] status = {1, 2, 1};
        for (int i = 0; i < 3; i++) {
            rows.add(new VariableBinding(new OID(SDPS_SENSOR_NAME).append(i + 1), new OctetString(names[i])));
        }
        for (int i = 0; i < 3; i++) {
            rows.add(new VariableBinding(new OID(SDPS_SENSOR_INPUT_STATUS).append(i + 1), new Integer32(status[i])));
        }
        return rows;
    }

    private static List<VariableBinding> alarmRow(int code, int instance, String name, int severity, String text,
                                                   Instant raisedAt) {
        return List.of(
                new VariableBinding(new OID(ALARM_INSTANCE_NAME).append(code).append(instance), new OctetString(name)),
                new VariableBinding(new OID(ALARM_SEVERITY).append(code).append(instance), new Integer32(severity)),
                new VariableBinding(new OID(ALARM_TEXT).append(code).append(instance), new OctetString(text)),
                new VariableBinding(new OID(ALARM_RAISED_AT).append(code).append(instance),
                        DateAndTimes.encode(raisedAt)));
    }

    private Observation mapWithAlarms(List<VariableBinding> rows) {
        return mapper.map(RAD_N, bindings(radar()), Map.of(ALARM_ENTRY, rows), T0);
    }

    @Test
    void alarmTableRowsBecomeDeviceAlarms() {
        Instant raised = Instant.parse("2026-11-13T14:00:00Z");
        List<VariableBinding> rows = new ArrayList<>();
        // a walk returns the table column by column; the rows here arrive out of (code, instance) order
        rows.addAll(alarmRow(201, 0, "", Severity.MAJOR.code(), "Channel degraded", raised.plusSeconds(60)));
        rows.addAll(alarmRow(101, 0, "", Severity.CRITICAL.code(), "Equipment failed", raised));

        Observation o = mapWithAlarms(rows);

        assertThat(o.alarms()).containsExactly(
                new DeviceAlarm(101, 0, "", Severity.CRITICAL, "Equipment failed", raised),
                new DeviceAlarm(201, 0, "", Severity.MAJOR, "Channel degraded", raised.plusSeconds(60)));
    }

    @Test
    void anEmptyAlarmTableGivesNoAlarms() {
        assertThat(mapWithAlarms(List.of()).alarms()).isEmpty();
    }

    @Test
    void aMalformedAlarmRowFailsTheObservation() {
        Instant raised = Instant.parse("2026-11-13T14:00:00Z");
        List<VariableBinding> missingSeverity = new ArrayList<>(alarmRow(101, 0, "", Severity.CRITICAL.code(), "x", raised));
        missingSeverity.removeIf(vb -> vb.getOid().startsWith(ALARM_SEVERITY));
        List<VariableBinding> unknownSeverity = alarmRow(101, 0, "", 99, "x", raised);

        for (List<VariableBinding> rows : List.of(missingSeverity, unknownSeverity)) {
            assertThatThrownBy(() -> mapWithAlarms(rows))
                    .isInstanceOfSatisfying(SnmpRequestException.class, e -> {
                        assertThat(e.kind()).isEqualTo(SnmpRequestException.Kind.MALFORMED);
                        assertThat(e.getMessage()).startsWith("MALFORMED: alarm table");
                    });
        }
    }

    @Test
    void mapsRadar() {
        Observation o = mapper.map(RAD_N, bindings(radar()), Map.of(), T0);

        assertThat(o.equipmentId()).isEqualTo("RAD-N");
        assertThat(o.observedAt()).isEqualTo(T0);
        assertThat(o.status()).isEqualTo(OPERATIONAL);
        assertThat(o.role()).isEqualTo(Role.STANDALONE);
        assertThat(o.maintenance()).isFalse();
        assertThat(o.values()).containsExactly(
                Map.entry("swVersion", "1.2.3"),
                Map.entry("statusChangedAt", "2026-11-13T09:00:00Z"),
                Map.entry("lastCommandResult", "SUCCESS"),
                Map.entry("rotationPeriodMs", 4000L),
                Map.entry("plotsPerScan", 300L),
                Map.entry("activeChannel", "CHANNEL_A"),
                Map.entry("channelAStatus", "OPERATIONAL"),
                Map.entry("channelBStatus", "OPERATIONAL"));
        assertThat(o.tables()).isEmpty();
    }

    @Test
    void mapsMaintenanceAndDegradedStandby() {
        Map<OID, Variable> m = equipment("RAD-N", 2, 3);
        m.put(EQ_MAINTENANCE_MODE, new Integer32(1));

        Observation o = mapper.map(RAD_N, bindings(m), Map.of(), T0);

        assertThat(o.status()).isEqualTo(EquipmentStatus.DEGRADED);
        assertThat(o.role()).isEqualTo(Role.STANDBY);
        assertThat(o.maintenance()).isTrue();
    }

    @Test
    void valuesAndMapsAreImmutable() {
        Observation o = mapper.map(RAD_N, bindings(radar()), Map.of(), T0);

        assertThatThrownBy(() -> o.values().put("x", 1L)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> o.tables().put("x", List.of())).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void mapsFdps() {
        Map<OID, Variable> m = equipment("FDPS-A", 1, 2);
        m.put(FDPS_FLIGHT_PLAN_COUNT, new Gauge32(120));
        m.put(FDPS_QUEUE_DEPTH, new Gauge32(3));
        m.put(FDPS_LATENCY_MS, new Gauge32(45));

        Observation o = mapper.map(FDPS_A, bindings(m), Map.of(), T0);

        assertThat(o.role()).isEqualTo(Role.ACTIVE);
        assertThat(o.values()).containsEntry("flightPlanCount", 120L).containsEntry("queueDepth", 3L)
                .containsEntry("latencyMs", 45L);
    }

    @Test
    void missingRequiredIsMalformed() {
        assertThatThrownBy(() -> mapper.map(RAD_N, bindings(without(radar(), EQ_STATUS)), Map.of(), T0))
                .isInstanceOf(SnmpRequestException.class)
                .hasMessage("MALFORMED: atmTsEqStatus missing");
    }

    @Test
    void exceptionValueOnRequiredIsMalformed() {
        assertThatThrownBy(() -> mapper.map(RAD_N, bindings(with(radar(), EQ_ROLE, Null.noSuchInstance)), Map.of(), T0))
                .hasMessage("MALFORMED: atmTsEqRole missing");
    }

    @Test
    void exceptionValueOnOptionalIsOmitted() {
        Observation o = mapper.map(RAD_N, bindings(with(radar(), RADAR_PLOTS_PER_SCAN, Null.noSuchInstance)),
                Map.of(), T0);

        assertThat(o.values()).doesNotContainKey("plotsPerScan").containsKey("rotationPeriodMs");
    }

    @Test
    void missingOptionalIsOmitted() {
        Observation o = mapper.map(RAD_N, bindings(without(radar(), EQ_SW_VERSION)), Map.of(), T0);

        assertThat(o.values()).doesNotContainKey("swVersion");
    }

    @Test
    void optionalWithWrongSyntaxIsOmitted() {
        Observation o = mapper.map(RAD_N, bindings(with(radar(), RADAR_PLOTS_PER_SCAN, new OctetString("x"))),
                Map.of(), T0);

        assertThat(o.values()).doesNotContainKey("plotsPerScan");
    }

    @Test
    void malformedDateAndTimeOmitsStatusChangedAt() {
        // month 13 makes DateAndTimes.decode throw DateTimeException; the field is optional
        OctetString bad = new OctetString(new byte[] {0x07, (byte) 0xEA, 13, 1, 0, 0, 0, 0});
        Observation o = mapper.map(RAD_N, bindings(with(radar(), EQ_STATUS_CHANGED_AT, bad)), Map.of(), T0);

        assertThat(o.values()).doesNotContainKey("statusChangedAt").containsKey("swVersion");
    }

    @Test
    void wrongLengthDateAndTimeOmitsStatusChangedAt() {
        Observation o = mapper.map(RAD_N,
                bindings(with(radar(), EQ_STATUS_CHANGED_AT, new OctetString(new byte[] {1, 2, 3}))), Map.of(), T0);

        assertThat(o.values()).doesNotContainKey("statusChangedAt");
    }

    @Test
    void wrongDeviceAnsweringIsMalformed() {
        assertThatThrownBy(() -> mapper.map(RAD_N, bindings(with(radar(), EQ_ID, new OctetString("RAD-E"))),
                Map.of(), T0)).hasMessage("MALFORMED: answered as RAD-E");
    }

    @Test
    void unknownEnumCodeIsMalformed() {
        assertThatThrownBy(() -> mapper.map(RAD_N, bindings(with(radar(), EQ_STATUS, new Integer32(9))), Map.of(), T0))
                .isInstanceOf(SnmpRequestException.class)
                .hasMessageContaining("Unknown EquipmentStatus code 9");
    }

    @Test
    void wrongSyntaxIsMalformed() {
        assertThatThrownBy(() -> mapper.map(RAD_N, bindings(with(radar(), EQ_STATUS, new OctetString("1"))),
                Map.of(), T0)).hasMessage("MALFORMED: atmTsEqStatus has syntax OCTET STRING");
    }

    @Test
    void counterSyntaxOnRequiredIsMalformed() {
        assertThatThrownBy(() -> mapper.map(RAD_N, bindings(with(radar(), EQ_STATUS, new Counter32(1))), Map.of(), T0))
                .isInstanceOf(SnmpRequestException.class).hasMessageStartingWith("MALFORMED: atmTsEqStatus has syntax");
    }

    @Test
    void mapsSdpsSensorTable() {
        Map<OID, Variable> m = equipment("SDPS-A", 1, 2);
        m.put(SDPS_TRACK_COUNT, new Gauge32(900));
        m.put(SDPS_LATENCY_MS, new Gauge32(20));

        Observation o = mapper.map(SDPS_A, bindings(m), Map.of(SDPS_SENSOR_ENTRY, sensorRows()), T0);

        assertThat(o.values()).containsEntry("trackCount", 900L).containsEntry("latencyMs", 20L);
        assertThat(o.tables().get("sensors")).containsExactly(
                Map.of("index", 1L, "name", "RAD-N", "inputStatus", SensorInputStatus.RECEIVING.name()),
                Map.of("index", 2L, "name", "RAD-E", "inputStatus", SensorInputStatus.LOST.name()),
                Map.of("index", 3L, "name", "RAD-S", "inputStatus", SensorInputStatus.RECEIVING.name()));
    }

    @Test
    void sensorRowsAreOrderedByIndexWhateverTheirOrder() {
        List<VariableBinding> rows = new ArrayList<>(sensorRows());
        java.util.Collections.reverse(rows);
        Map<OID, Variable> m = equipment("SDPS-A", 1, 2);

        Observation o = mapper.map(SDPS_A, bindings(m), Map.of(SDPS_SENSOR_ENTRY, rows), T0);

        assertThat(o.tables().get("sensors")).extracting(r -> r.get("index")).containsExactly(1L, 2L, 3L);
    }

    @Test
    void mapsProbe() {
        assertThat(mapper.mapProbe(RAD_N, List.of(new VariableBinding(SnmpConstants.sysUpTime, new TimeTicks(1234))),
                T0)).isEqualTo(new ProbeObservation("RAD-N", T0, 1234));
    }

    @Test
    void probeWithoutUptimeIsMalformed() {
        assertThatThrownBy(() -> mapper.mapProbe(RAD_N,
                List.of(new VariableBinding(SnmpConstants.sysUpTime, Null.noSuchObject)), T0))
                .hasMessage("MALFORMED: sysUpTime missing");
        assertThatThrownBy(() -> mapper.mapProbe(RAD_N, List.of(), T0)).hasMessage("MALFORMED: sysUpTime missing");
    }

    @Test
    void probeWithWrongSyntaxIsMalformed() {
        assertThatThrownBy(() -> mapper.mapProbe(RAD_N,
                List.of(new VariableBinding(SnmpConstants.sysUpTime, new Integer32(5))), T0))
                .hasMessage("MALFORMED: sysUpTime missing");
    }

    @Test
    void pollPlanPerType() {
        assertThat(PollPlan.forType(DeviceType.RADAR).scalars()).hasSize(14);
        assertThat(PollPlan.forType(DeviceType.RADAR).walks()).containsExactly(AtmTsOids.ALARM_ENTRY);
        PollPlan sdps = PollPlan.forType(DeviceType.SDPS);
        assertThat(sdps.scalars()).hasSize(11).contains(scalar(SDPS_TRACK_COUNT), scalar(EQ_STATUS));
        assertThat(sdps.walks()).containsExactly(AtmTsOids.SDPS_SENSOR_ENTRY, AtmTsOids.ALARM_ENTRY);
        assertThat(PollPlan.forType(DeviceType.FDPS).scalars()).hasSize(12);
        assertThat(PollPlan.forType(DeviceType.FDPS).walks()).containsExactly(AtmTsOids.ALARM_ENTRY);
        assertThatThrownBy(() -> PollPlan.forType(DeviceType.ADSB)).isInstanceOf(IllegalArgumentException.class);
        assertThat(PollPlan.PROBE).isEqualTo(SnmpConstants.sysUpTime);
    }
}
