package io.github.selvitopu.atmsup.supervisor.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmKey;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmSource;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmState;
import io.github.selvitopu.atmsup.supervisor.alarms.Via;
import io.github.selvitopu.atmsup.supervisor.board.Contact;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.AlarmEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.EquipmentEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.ServiceEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.SupervisorEvent;
import io.github.selvitopu.atmsup.supervisor.board.DomainEvent.TelemetrySampleEvent;
import io.github.selvitopu.atmsup.supervisor.board.EquipmentState;
import io.github.selvitopu.atmsup.supervisor.board.EventType;
import io.github.selvitopu.atmsup.supervisor.board.Health;
import io.github.selvitopu.atmsup.supervisor.board.ServiceId;
import io.github.selvitopu.atmsup.supervisor.board.ServiceState;
import io.github.selvitopu.atmsup.supervisor.board.ServiceStatus;
import io.github.selvitopu.atmsup.supervisor.board.StoredEvent;
import io.github.selvitopu.atmsup.supervisor.board.UnknownReason;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventSummariesTest {

    private static final Instant T = Instant.parse("2026-01-01T10:00:00Z");

    private static StoredEvent stored(DomainEvent e) {
        return new StoredEvent(1, e);
    }

    private static Alarm alarm(int code, int instance, String equipment, Severity severity, String text) {
        return new Alarm("a1", new AlarmKey(equipment, code, instance), "", severity, text, AlarmSource.DEVICE,
                AlarmState.ACTIVE_UNACK, false, T, null, null, null, null);
    }

    private static AlarmEvent alarmEvent(EventType type, Alarm a) {
        return new AlarmEvent(type, T, a, Via.TRAP, "demo-user");
    }

    private static EquipmentState equipment(Health h, UnknownReason r) {
        return new EquipmentState("RAD-N", h, r, false, Role.ACTIVE, T, new Contact(true, T, null));
    }

    @Test
    void equipmentStateChange() {
        var e = new EquipmentEvent(EventType.EQUIPMENT_STATE_CHANGED, T, equipment(Health.OPERATIONAL, null));
        assertThat(EventSummaries.describe(stored(e)))
                .isEqualTo("Health OPERATIONAL · role ACTIVE · maintenance off");
    }

    @Test
    void unknownHealthStatesItsReason() {
        var e = new EquipmentEvent(EventType.EQUIPMENT_STATE_CHANGED, T,
                equipment(Health.UNKNOWN, UnknownReason.AWAITING_STATUS));
        assertThat(EventSummaries.describe(stored(e)))
                .isEqualTo("Health UNKNOWN (AWAITING_STATUS) · role ACTIVE · maintenance off");
    }

    @Test
    void maintenanceOn() {
        var state = new EquipmentState("RAD-N", Health.DEGRADED, null, true, Role.STANDBY, T,
                new Contact(true, T, null));
        var e = new EquipmentEvent(EventType.EQUIPMENT_STATE_CHANGED, T, state);
        assertThat(EventSummaries.describe(stored(e)))
                .isEqualTo("Health DEGRADED · role STANDBY · maintenance on");
    }

    @Test
    void contactEvents() {
        var lost = new EquipmentEvent(EventType.CONTACT_LOST, T, equipment(Health.UNKNOWN, UnknownReason.LOST_CONTACT));
        var back = new EquipmentEvent(EventType.CONTACT_RESTORED, T,
                equipment(Health.UNKNOWN, UnknownReason.AWAITING_STATUS));
        assertThat(EventSummaries.describe(stored(lost))).isEqualTo("Lost contact");
        assertThat(EventSummaries.describe(stored(back))).isEqualTo("Contact restored");
    }

    @Test
    void alarmEvents() {
        Alarm a = alarm(401, 1, "RAD-N", Severity.MINOR, "Sensor input lost");
        assertThat(EventSummaries.describe(stored(alarmEvent(EventType.ALARM_RAISED, a))))
                .isEqualTo("Alarm raised: 401 Sensor input lost (RAD-N), MINOR");
        Alarm major = alarm(401, 1, "RAD-N", Severity.MAJOR, "Sensor input lost");
        assertThat(EventSummaries.describe(stored(alarmEvent(EventType.ALARM_UPDATED, major))))
                .isEqualTo("Alarm updated: 401 Sensor input lost (RAD-N), MAJOR");
        Alarm a101 = alarm(101, 0, "SDPS-A", Severity.CRITICAL, "Unit failed");
        assertThat(EventSummaries.describe(stored(alarmEvent(EventType.ALARM_ACKNOWLEDGED, a101))))
                .isEqualTo("Alarm acknowledged by demo-user: 101");
        assertThat(EventSummaries.describe(stored(alarmEvent(EventType.ALARM_CLEARED, a101))))
                .isEqualTo("Alarm cleared: 101");
        assertThat(EventSummaries.describe(stored(alarmEvent(EventType.ALARM_CLOSED, a101))))
                .isEqualTo("Alarm closed: 101");
    }

    @Test
    void serviceState() {
        var state = new ServiceState(ServiceId.SURV, ServiceStatus.DEGRADED, null, false, List.of(), T);
        assertThat(EventSummaries.describe(stored(new ServiceEvent(T, state)))).isEqualTo("Surveillance DEGRADED");
    }

    @Test
    void supervisorEvents() {
        assertThat(EventSummaries.describe(stored(new SupervisorEvent(EventType.SUPERVISOR_STARTED, T, null))))
                .isEqualTo("Supervisor started");
        assertThat(EventSummaries.describe(stored(new SupervisorEvent(EventType.SUPERVISOR_HEARTBEAT, T, null))))
                .isEqualTo("Supervisor heartbeat");
        assertThat(EventSummaries.describe(stored(new SupervisorEvent(EventType.SUPERVISOR_STOPPING, T, null))))
                .isEqualTo("Supervisor stopping");
    }

    @Test
    void telemetrySample() {
        var e = new TelemetrySampleEvent(T, "RAD-N", T, Map.of(), Map.of());
        assertThat(EventSummaries.describe(stored(e))).isEqualTo("Telemetry sampled");
    }
}
