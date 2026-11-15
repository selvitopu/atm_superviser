--liquibase formatted sql

--changeset atm-supervisor:002-users-and-roles
CREATE TABLE role (
  name VARCHAR(16) PRIMARY KEY,
  description VARCHAR(128) NOT NULL
);
CREATE TABLE app_user (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  username VARCHAR(32) NOT NULL UNIQUE,
  display_name VARCHAR(64) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  -- one account per name, whatever case it was typed in
  CONSTRAINT ck_app_user_username CHECK (username = lower(username) AND char_length(username) >= 3)
);
CREATE TABLE user_role (
  user_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
  role_name VARCHAR(16) NOT NULL REFERENCES role (name),
  PRIMARY KEY (user_id, role_name)
);

--changeset atm-supervisor:002-roles
INSERT INTO role (name, description) VALUES
  ('VIEWER', 'Read-only board access'),
  ('SUPERVISOR', 'Acknowledge alarms and operate equipment'),
  ('ADMIN', 'Manage users and view request history');
