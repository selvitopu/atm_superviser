package io.github.selvitopu.atmsup.supervisor.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One row of the append-only event log; {@code payload} is the JSON written by {@link EventCodec}. */
@Entity
@Table(name = "event_log")
public class EventLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "type", nullable = false, length = 40)
    private String type;

    @Column(name = "entity_kind", nullable = false, length = 16)
    private String entityKind;

    @Column(name = "entity_id", length = 64)
    private String entityId;

    @Column(name = "equipment_id", length = 16)
    private String equipmentId;

    @Column(name = "actor", length = 64)
    private String actor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    protected EventLogEntity() {
    }

    public EventLogEntity(Instant occurredAt, Instant recordedAt, String type, String entityKind, String entityId,
                          String equipmentId, String actor, String payload) {
        this.occurredAt = occurredAt;
        this.recordedAt = recordedAt;
        this.type = type;
        this.entityKind = entityKind;
        this.entityId = entityId;
        this.equipmentId = equipmentId;
        this.actor = actor;
        this.payload = payload;
    }

    public Long getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public String getType() {
        return type;
    }

    public String getEntityKind() {
        return entityKind;
    }

    public String getEntityId() {
        return entityId;
    }

    public String getEquipmentId() {
        return equipmentId;
    }

    public String getActor() {
        return actor;
    }

    public String getPayload() {
        return payload;
    }
}
