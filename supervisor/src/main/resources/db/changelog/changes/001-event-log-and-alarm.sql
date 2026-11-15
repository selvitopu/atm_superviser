--liquibase formatted sql

--changeset atm-supervisor:001-event-log
CREATE TABLE event_log (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  occurred_at TIMESTAMPTZ(3) NOT NULL,
  recorded_at TIMESTAMPTZ(3) NOT NULL,
  type VARCHAR(40) NOT NULL,
  entity_kind VARCHAR(16) NOT NULL,
  entity_id VARCHAR(64) NULL,
  equipment_id VARCHAR(16) NULL,
  actor VARCHAR(64) NULL,
  payload JSONB NOT NULL
);
CREATE INDEX ix_event_log_occurred ON event_log (occurred_at);
CREATE INDEX ix_event_log_equipment ON event_log (equipment_id, occurred_at);
CREATE INDEX ix_event_log_type ON event_log (type, occurred_at);

--changeset atm-supervisor:001-alarm
CREATE TABLE alarm (
  id VARCHAR(36) PRIMARY KEY,
  equipment_id VARCHAR(16) NOT NULL,
  code INTEGER NOT NULL,
  instance INTEGER NOT NULL,
  instance_name VARCHAR(64) NOT NULL DEFAULT '',
  severity VARCHAR(16) NOT NULL,
  text VARCHAR(255) NOT NULL,
  source VARCHAR(16) NOT NULL,
  state VARCHAR(16) NOT NULL,
  suppressed BOOLEAN NOT NULL,
  raised_at TIMESTAMPTZ(3) NOT NULL,
  acked_at TIMESTAMPTZ(3) NULL,
  acked_by VARCHAR(64) NULL,
  ack_comment VARCHAR(500) NULL,
  cleared_at TIMESTAMPTZ(3) NULL
);
-- At most one alarm per (equipment, code, instance) is open; a CLOSED alarm frees its identity.
CREATE UNIQUE INDEX uk_alarm_open ON alarm (equipment_id, code, instance) WHERE state <> 'CLOSED';
CREATE INDEX ix_alarm_state ON alarm (state);
CREATE INDEX ix_alarm_raised ON alarm (raised_at);
