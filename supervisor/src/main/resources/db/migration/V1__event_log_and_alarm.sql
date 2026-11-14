CREATE TABLE event_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, occurred_at DATETIME(3) NOT NULL, recorded_at DATETIME(3) NOT NULL,
  type VARCHAR(40) NOT NULL, entity_kind VARCHAR(16) NOT NULL, entity_id VARCHAR(64) NULL, equipment_id VARCHAR(16) NULL,
  actor VARCHAR(64) NULL, payload JSON NOT NULL,
  INDEX ix_event_log_occurred (occurred_at), INDEX ix_event_log_equipment (equipment_id, occurred_at),
  INDEX ix_event_log_type (type, occurred_at));
CREATE TABLE alarm (
  id CHAR(36) PRIMARY KEY, equipment_id VARCHAR(16) NOT NULL, code INT NOT NULL, instance INT NOT NULL,
  instance_name VARCHAR(64) NOT NULL DEFAULT '', severity VARCHAR(16) NOT NULL, text VARCHAR(255) NOT NULL,
  source VARCHAR(16) NOT NULL, state VARCHAR(16) NOT NULL, suppressed BOOLEAN NOT NULL,
  raised_at DATETIME(3) NOT NULL, acked_at DATETIME(3) NULL, acked_by VARCHAR(64) NULL, ack_comment VARCHAR(500) NULL,
  cleared_at DATETIME(3) NULL, open_key VARCHAR(64) NULL,
  UNIQUE KEY uk_alarm_open_key (open_key), INDEX ix_alarm_state (state), INDEX ix_alarm_raised (raised_at));
