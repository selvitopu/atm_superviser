package io.github.selvitopu.atmsup.mib;

import org.snmp4j.smi.OID;

/**
 * OIDs of DEMO-ATM-TECHSUP-MIB. Column and object OIDs are given without an instance suffix; use
 * {@link #scalar(OID)} for scalar instances. {@code AtmTsOidsTest} keeps these constants in sync with the MIB file.
 */
public final class AtmTsOids {

    private AtmTsOids() {}

    /** atmTechSupMIB, enterprises.32473.1 (documentation enterprise number, RFC 5612). */
    public static final OID ROOT = new OID("1.3.6.1.4.1.32473.1");

    private static final OID OBJECTS = child(ROOT, 1);

    // atmTsEquipment (B.1.1)
    private static final OID EQUIPMENT = child(OBJECTS, 1);
    public static final OID EQ_ID = child(EQUIPMENT, 1);
    public static final OID EQ_TYPE = child(EQUIPMENT, 2);
    public static final OID EQ_STATUS = child(EQUIPMENT, 3);
    public static final OID EQ_ROLE = child(EQUIPMENT, 4);
    public static final OID EQ_STATUS_CHANGED_AT = child(EQUIPMENT, 5);
    public static final OID EQ_SW_VERSION = child(EQUIPMENT, 6);
    public static final OID EQ_MAINTENANCE_MODE = child(EQUIPMENT, 7);
    public static final OID EQ_COMMAND = child(EQUIPMENT, 8);
    public static final OID EQ_LAST_COMMAND_RESULT = child(EQUIPMENT, 9);

    // atmTsAlarms (B.1.2): table at .1, notify-only objects at .2 and .3
    private static final OID ALARMS = child(OBJECTS, 2);
    public static final OID ALARM_ENTRY = child(child(ALARMS, 1), 1);
    public static final OID ALARM_CODE = child(ALARM_ENTRY, 1);
    public static final OID ALARM_INSTANCE = child(ALARM_ENTRY, 2);
    public static final OID ALARM_INSTANCE_NAME = child(ALARM_ENTRY, 3);
    public static final OID ALARM_SEVERITY = child(ALARM_ENTRY, 4);
    public static final OID ALARM_TEXT = child(ALARM_ENTRY, 5);
    public static final OID ALARM_RAISED_AT = child(ALARM_ENTRY, 6);
    public static final OID NOTIF_ALARM_CODE = child(ALARMS, 2);
    public static final OID NOTIF_ALARM_INSTANCE = child(ALARMS, 3);

    // atmTsRadar (B.1.3)
    private static final OID RADAR = child(OBJECTS, 3);
    public static final OID RADAR_ROTATION_PERIOD_MS = child(RADAR, 1);
    public static final OID RADAR_PLOTS_PER_SCAN = child(RADAR, 2);
    public static final OID RADAR_ACTIVE_CHANNEL = child(RADAR, 3);
    public static final OID RADAR_CHANNEL_A_STATUS = child(RADAR, 4);
    public static final OID RADAR_CHANNEL_B_STATUS = child(RADAR, 5);

    // atmTsAdsb (B.1.4)
    private static final OID ADSB = child(OBJECTS, 4);
    public static final OID ADSB_TARGETS_PER_SECOND = child(ADSB, 1);
    public static final OID ADSB_STATIONS_UP = child(ADSB, 2);
    public static final OID ADSB_STATIONS_TOTAL = child(ADSB, 3);

    // atmTsSdps (B.1.5)
    private static final OID SDPS = child(OBJECTS, 5);
    public static final OID SDPS_TRACK_COUNT = child(SDPS, 1);
    public static final OID SDPS_LATENCY_MS = child(SDPS, 2);
    public static final OID SDPS_SENSOR_ENTRY = child(child(SDPS, 3), 1);
    public static final OID SDPS_SENSOR_INDEX = child(SDPS_SENSOR_ENTRY, 1);
    public static final OID SDPS_SENSOR_NAME = child(SDPS_SENSOR_ENTRY, 2);
    public static final OID SDPS_SENSOR_INPUT_STATUS = child(SDPS_SENSOR_ENTRY, 3);

    // atmTsFdps (B.1.6)
    private static final OID FDPS = child(OBJECTS, 6);
    public static final OID FDPS_FLIGHT_PLAN_COUNT = child(FDPS, 1);
    public static final OID FDPS_QUEUE_DEPTH = child(FDPS, 2);
    public static final OID FDPS_LATENCY_MS = child(FDPS, 3);

    // atmTsVcs (B.1.7)
    private static final OID VCS = child(OBJECTS, 7);
    public static final OID VCS_POSITIONS_ACTIVE = child(VCS, 1);
    public static final OID VCS_POSITIONS_TOTAL = child(VCS, 2);
    public static final OID VCS_RADIO_LINK_ENTRY = child(child(VCS, 3), 1);
    public static final OID VCS_RADIO_LINK_INDEX = child(VCS_RADIO_LINK_ENTRY, 1);
    public static final OID VCS_RADIO_LINK_SITE_NAME = child(VCS_RADIO_LINK_ENTRY, 2);
    public static final OID VCS_RADIO_LINK_STATUS = child(VCS_RADIO_LINK_ENTRY, 3);

    // atmTsRadio (B.1.8)
    private static final OID RADIO = child(OBJECTS, 8);
    public static final OID RADIO_FREQ_ENTRY = child(child(RADIO, 1), 1);
    public static final OID RADIO_FREQ_INDEX = child(RADIO_FREQ_ENTRY, 1);
    public static final OID RADIO_FREQ_MHZ = child(RADIO_FREQ_ENTRY, 2);
    public static final OID RADIO_FREQ_SECTOR = child(RADIO_FREQ_ENTRY, 3);
    public static final OID RADIO_MAIN_TX_STATUS = child(RADIO_FREQ_ENTRY, 4);
    public static final OID RADIO_STANDBY_TX_STATUS = child(RADIO_FREQ_ENTRY, 5);
    public static final OID RADIO_ACTIVE_TX = child(RADIO_FREQ_ENTRY, 6);
    public static final OID RADIO_RX_STATUS = child(RADIO_FREQ_ENTRY, 7);

    // atmTsRecorder (B.1.9)
    private static final OID RECORDER = child(OBJECTS, 9);
    public static final OID REC_CHANNELS_RECORDING = child(RECORDER, 1);
    public static final OID REC_CHANNELS_TOTAL = child(RECORDER, 2);
    public static final OID REC_DISK_USAGE_PERCENT = child(RECORDER, 3);
    public static final OID REC_LAST_ARCHIVE_AT = child(RECORDER, 4);

    // atmTsNotifications (B.2), notification prefix atmTsNotificationsPrefix (B.2.0)
    private static final OID NOTIFICATIONS = child(child(ROOT, 2), 0);
    public static final OID NOTIF_EQ_STATUS_CHANGE = child(NOTIFICATIONS, 1);
    public static final OID NOTIF_ALARM_RAISED = child(NOTIFICATIONS, 2);
    public static final OID NOTIF_ALARM_CLEARED = child(NOTIFICATIONS, 3);
    public static final OID NOTIF_SWITCHOVER = child(NOTIFICATIONS, 4);

    /** Returns the instance OID of a scalar object: the object OID followed by {@code .0}. */
    public static OID scalar(OID object) {
        return child(object, 0);
    }

    private static OID child(OID parent, int sub) {
        OID oid = new OID(parent);
        oid.append(sub);
        return oid;
    }
}
