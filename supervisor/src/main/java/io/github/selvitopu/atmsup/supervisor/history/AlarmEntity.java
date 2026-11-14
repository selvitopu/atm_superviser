package io.github.selvitopu.atmsup.supervisor.history;

import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.supervisor.alarms.Alarm;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmKey;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmSource;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The current state of one alarm. {@code open_key} is unique and set for every alarm that is not CLOSED, so at most
 * one alarm per (equipment, code, instance) is open. For the service alarms 903 and 904 {@code equipment_id} holds
 * the service id.
 */
@Entity
@Table(name = "alarm")
public class AlarmEntity {

    @Id
    @Column(name = "id", columnDefinition = "char(36)")
    private String id;

    @Column(name = "equipment_id", nullable = false, length = 16)
    private String equipmentId;

    @Column(name = "code", nullable = false)
    private int code;

    @Column(name = "instance", nullable = false)
    private int instance;

    @Column(name = "instance_name", nullable = false, length = 64)
    private String instanceName;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "severity", nullable = false, length = 16)
    private Severity severity;

    @Column(name = "text", nullable = false, length = 255)
    private String text;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "source", nullable = false, length = 16)
    private AlarmSource source;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "state", nullable = false, length = 16)
    private AlarmState state;

    @Column(name = "suppressed", nullable = false)
    private boolean suppressed;

    @Column(name = "raised_at", nullable = false, columnDefinition = "datetime(3)")
    private Instant raisedAt;

    @Column(name = "acked_at", columnDefinition = "datetime(3)")
    private Instant ackedAt;

    @Column(name = "acked_by", length = 64)
    private String ackedBy;

    @Column(name = "ack_comment", length = 500)
    private String ackComment;

    @Column(name = "cleared_at", columnDefinition = "datetime(3)")
    private Instant clearedAt;

    @Column(name = "open_key", length = 64)
    private String openKey;

    protected AlarmEntity() {
    }

    public AlarmEntity(Alarm alarm) {
        this.id = alarm.id();
        update(alarm);
    }

    /** Copies the alarm's new state; the id never changes. */
    public void update(Alarm a) {
        if (!id.equals(a.id())) {
            throw new IllegalArgumentException("Alarm " + a.id() + " cannot update alarm " + id);
        }
        equipmentId = a.key().equipmentId();
        code = a.key().code();
        instance = a.key().instance();
        instanceName = a.instanceName() == null ? "" : a.instanceName();
        severity = a.severity();
        text = a.text();
        source = a.source();
        state = a.state();
        suppressed = a.suppressed();
        raisedAt = a.raisedAt();
        ackedAt = a.ackedAt();
        ackedBy = a.ackedBy();
        ackComment = a.ackComment();
        clearedAt = a.clearedAt();
        openKey = a.state() == AlarmState.CLOSED ? null : a.key().openKey();
    }

    public Alarm toAlarm() {
        return new Alarm(id, new AlarmKey(equipmentId, code, instance), instanceName, severity, text, source, state,
                suppressed, raisedAt, ackedAt, ackedBy, ackComment, clearedAt);
    }
}
