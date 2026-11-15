--liquibase formatted sql

-- Demo accounts, loaded only in demo mode (context "demo"). Their passwords are documented in the README and stored
-- here only as bcrypt hashes in Spring Security's DelegatingPasswordEncoder format.

--changeset atm-supervisor:003-demo-users context:demo
INSERT INTO app_user (username, display_name, password_hash) VALUES
  ('admin1', 'Admin One', '{bcrypt}$2a$10$WXb2QSx.u295hZ4Kl9z1o.MwuPX8ZGBW2E1NUoalABb635udwUsj2'),
  ('supervisor1', 'Supervisor One', '{bcrypt}$2a$10$4xEQN3NdR1GVkxAtaqHImOvK6QwGLnv7ltohPuM4sjdTjN.F.GLZ.'),
  ('demo1', 'Demo One', '{bcrypt}$2a$10$xVuld2mm.SqIt.PPcLfeX.W0GCJE/AGnOxWehqEnsASJKPFCKIqci');
INSERT INTO user_role (user_id, role_name)
  SELECT id, 'ADMIN' FROM app_user WHERE username = 'admin1'
  UNION ALL SELECT id, 'SUPERVISOR' FROM app_user WHERE username = 'supervisor1'
  UNION ALL SELECT id, 'VIEWER' FROM app_user WHERE username = 'demo1';
