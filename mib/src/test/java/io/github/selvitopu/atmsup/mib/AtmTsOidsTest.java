package io.github.selvitopu.atmsup.mib;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.snmp4j.smi.OID;

class AtmTsOidsTest {

    private static final Path MIB_FILE = Path.of("DEMO-ATM-TECHSUP-MIB.txt");

    private static final Map<String, OID> EXPECTED = Map.ofEntries(
            entry("atmTechSupMIB", AtmTsOids.ROOT),
            // equipment
            entry("atmTsEqId", AtmTsOids.EQ_ID),
            entry("atmTsEqType", AtmTsOids.EQ_TYPE),
            entry("atmTsEqStatus", AtmTsOids.EQ_STATUS),
            entry("atmTsEqRole", AtmTsOids.EQ_ROLE),
            entry("atmTsEqStatusChangedAt", AtmTsOids.EQ_STATUS_CHANGED_AT),
            entry("atmTsEqSwVersion", AtmTsOids.EQ_SW_VERSION),
            entry("atmTsEqMaintenanceMode", AtmTsOids.EQ_MAINTENANCE_MODE),
            entry("atmTsEqCommand", AtmTsOids.EQ_COMMAND),
            entry("atmTsEqLastCommandResult", AtmTsOids.EQ_LAST_COMMAND_RESULT),
            // alarms
            entry("atmTsAlarmEntry", AtmTsOids.ALARM_ENTRY),
            entry("atmTsAlarmCode", AtmTsOids.ALARM_CODE),
            entry("atmTsAlarmInstance", AtmTsOids.ALARM_INSTANCE),
            entry("atmTsAlarmInstanceName", AtmTsOids.ALARM_INSTANCE_NAME),
            entry("atmTsAlarmSeverity", AtmTsOids.ALARM_SEVERITY),
            entry("atmTsAlarmText", AtmTsOids.ALARM_TEXT),
            entry("atmTsAlarmRaisedAt", AtmTsOids.ALARM_RAISED_AT),
            entry("atmTsNotifAlarmCode", AtmTsOids.NOTIF_ALARM_CODE),
            entry("atmTsNotifAlarmInstance", AtmTsOids.NOTIF_ALARM_INSTANCE),
            // radar
            entry("atmTsRadarRotationPeriodMs", AtmTsOids.RADAR_ROTATION_PERIOD_MS),
            entry("atmTsRadarPlotsPerScan", AtmTsOids.RADAR_PLOTS_PER_SCAN),
            entry("atmTsRadarActiveChannel", AtmTsOids.RADAR_ACTIVE_CHANNEL),
            entry("atmTsRadarChannelAStatus", AtmTsOids.RADAR_CHANNEL_A_STATUS),
            entry("atmTsRadarChannelBStatus", AtmTsOids.RADAR_CHANNEL_B_STATUS),
            // ADS-B
            entry("atmTsAdsbTargetsPerSecond", AtmTsOids.ADSB_TARGETS_PER_SECOND),
            entry("atmTsAdsbStationsUp", AtmTsOids.ADSB_STATIONS_UP),
            entry("atmTsAdsbStationsTotal", AtmTsOids.ADSB_STATIONS_TOTAL),
            // SDPS
            entry("atmTsSdpsTrackCount", AtmTsOids.SDPS_TRACK_COUNT),
            entry("atmTsSdpsLatencyMs", AtmTsOids.SDPS_LATENCY_MS),
            entry("atmTsSdpsSensorEntry", AtmTsOids.SDPS_SENSOR_ENTRY),
            entry("atmTsSdpsSensorIndex", AtmTsOids.SDPS_SENSOR_INDEX),
            entry("atmTsSdpsSensorName", AtmTsOids.SDPS_SENSOR_NAME),
            entry("atmTsSdpsSensorInputStatus", AtmTsOids.SDPS_SENSOR_INPUT_STATUS),
            // FDPS
            entry("atmTsFdpsFlightPlanCount", AtmTsOids.FDPS_FLIGHT_PLAN_COUNT),
            entry("atmTsFdpsQueueDepth", AtmTsOids.FDPS_QUEUE_DEPTH),
            entry("atmTsFdpsLatencyMs", AtmTsOids.FDPS_LATENCY_MS),
            // VCS
            entry("atmTsVcsPositionsActive", AtmTsOids.VCS_POSITIONS_ACTIVE),
            entry("atmTsVcsPositionsTotal", AtmTsOids.VCS_POSITIONS_TOTAL),
            entry("atmTsVcsRadioLinkEntry", AtmTsOids.VCS_RADIO_LINK_ENTRY),
            entry("atmTsVcsRadioLinkIndex", AtmTsOids.VCS_RADIO_LINK_INDEX),
            entry("atmTsVcsRadioLinkSiteName", AtmTsOids.VCS_RADIO_LINK_SITE_NAME),
            entry("atmTsVcsRadioLinkStatus", AtmTsOids.VCS_RADIO_LINK_STATUS),
            // radio
            entry("atmTsRadioFreqEntry", AtmTsOids.RADIO_FREQ_ENTRY),
            entry("atmTsRadioFreqIndex", AtmTsOids.RADIO_FREQ_INDEX),
            entry("atmTsRadioFreqMHz", AtmTsOids.RADIO_FREQ_MHZ),
            entry("atmTsRadioFreqSector", AtmTsOids.RADIO_FREQ_SECTOR),
            entry("atmTsRadioMainTxStatus", AtmTsOids.RADIO_MAIN_TX_STATUS),
            entry("atmTsRadioStandbyTxStatus", AtmTsOids.RADIO_STANDBY_TX_STATUS),
            entry("atmTsRadioActiveTx", AtmTsOids.RADIO_ACTIVE_TX),
            entry("atmTsRadioRxStatus", AtmTsOids.RADIO_RX_STATUS),
            // recorder
            entry("atmTsRecorderChannelsRecording", AtmTsOids.REC_CHANNELS_RECORDING),
            entry("atmTsRecorderChannelsTotal", AtmTsOids.REC_CHANNELS_TOTAL),
            entry("atmTsRecorderDiskUsagePercent", AtmTsOids.REC_DISK_USAGE_PERCENT),
            entry("atmTsRecorderLastArchiveAt", AtmTsOids.REC_LAST_ARCHIVE_AT),
            // notifications
            entry("atmTsEqStatusChange", AtmTsOids.NOTIF_EQ_STATUS_CHANGE),
            entry("atmTsAlarmRaised", AtmTsOids.NOTIF_ALARM_RAISED),
            entry("atmTsAlarmCleared", AtmTsOids.NOTIF_ALARM_CLEARED),
            entry("atmTsSwitchover", AtmTsOids.NOTIF_SWITCHOVER));

    @Test
    void everyConstantMatchesTheMibFile() throws Exception {
        Map<String, OID> resolved = MibFileParser.resolve(MIB_FILE);

        EXPECTED.forEach((name, oid) -> assertThat(resolved.get(name)).as(name).isEqualTo(oid));
    }

    @Test
    void everyPublicOidConstantIsCoveredByTheMappingAbove() {
        List<OID> constants = new ArrayList<>();
        for (Field field : AtmTsOids.class.getFields()) {
            int mod = field.getModifiers();
            if (Modifier.isStatic(mod) && Modifier.isFinal(mod) && field.getType() == OID.class) {
                try {
                    constants.add((OID) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            }
        }

        assertThat(constants).hasSize(EXPECTED.size()).containsExactlyInAnyOrderElementsOf(EXPECTED.values());
    }

    @Test
    void scalarAppendsInstanceZero() {
        assertThat(AtmTsOids.EQ_ID).isEqualTo(new OID("1.3.6.1.4.1.32473.1.1.1.1"));
        assertThat(AtmTsOids.scalar(AtmTsOids.EQ_ID)).isEqualTo(new OID("1.3.6.1.4.1.32473.1.1.1.1.0"));
    }

    /** Minimal OID resolver for the assignments in an SMIv2 module. */
    static final class MibFileParser {

        private static final Pattern ASSIGNMENT = Pattern.compile(
                "(\\w+)\\s+(?:OBJECT-TYPE|OBJECT IDENTIFIER|NOTIFICATION-TYPE|MODULE-IDENTITY|OBJECT-GROUP"
                        + "|NOTIFICATION-GROUP|MODULE-COMPLIANCE)[\\s\\S]*?::=\\s*\\{\\s*(\\w+)((?:\\s+\\d+)+)\\s*\\}");

        private MibFileParser() {}

        static Map<String, OID> resolve(Path file) throws Exception {
            String text = Files.readString(file).replaceAll("--[^\\n]*", "").replaceAll("IMPORTS[^;]*;", "");
            Map<String, OID> known = new HashMap<>();
            known.put("enterprises", new OID("1.3.6.1.4.1"));

            Map<String, String[]> pending = new LinkedHashMap<>();
            Matcher m = ASSIGNMENT.matcher(text);
            while (m.find()) {
                pending.put(m.group(1), new String[] {m.group(2), m.group(3).trim().replaceAll("\\s+", ".")});
            }
            boolean progress = true;
            while (!pending.isEmpty() && progress) {
                progress = false;
                var it = pending.entrySet().iterator();
                while (it.hasNext()) {
                    var e = it.next();
                    OID parent = known.get(e.getValue()[0]);
                    if (parent != null) {
                        OID oid = new OID(parent);
                        oid.append(new OID(e.getValue()[1]));
                        known.put(e.getKey(), oid);
                        it.remove();
                        progress = true;
                    }
                }
            }
            assertThat(pending).as("unresolvable assignments").isEmpty();
            return known;
        }
    }
}
