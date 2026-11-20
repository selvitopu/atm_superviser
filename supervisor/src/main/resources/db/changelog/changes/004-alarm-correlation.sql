--liquibase formatted sql

--changeset atm-supervisor:004-alarm-correlation
ALTER TABLE alarm ADD COLUMN cause_id VARCHAR(36) NULL;
ALTER TABLE alarm ADD COLUMN cause_equipment_id VARCHAR(16) NULL;
ALTER TABLE alarm ADD COLUMN cause_code INTEGER NULL;
ALTER TABLE alarm ADD COLUMN correlation_rule VARCHAR(16) NULL;
-- a correlation is stored whole or not at all
ALTER TABLE alarm ADD CONSTRAINT ck_alarm_correlation CHECK (
  (cause_id IS NULL AND cause_equipment_id IS NULL AND cause_code IS NULL AND correlation_rule IS NULL)
  OR (cause_id IS NOT NULL AND cause_equipment_id IS NOT NULL AND cause_code IS NOT NULL
      AND correlation_rule IS NOT NULL));
