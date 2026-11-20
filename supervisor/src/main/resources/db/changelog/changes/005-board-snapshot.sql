--liquibase formatted sql

--changeset atm-supervisor:005-board-snapshot
CREATE TABLE board_snapshot (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  taken_at TIMESTAMPTZ(3) NOT NULL,
  last_event_id BIGINT NOT NULL,
  schema_version INT NOT NULL,
  board JSONB NOT NULL
);
CREATE INDEX ix_board_snapshot_taken ON board_snapshot (taken_at);
