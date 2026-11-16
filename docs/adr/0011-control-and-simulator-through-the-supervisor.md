# ADR-0011: Equipment Control, the Simulator Console and Account Administration Go Through the Supervisor

* **Status:** Accepted — Implemented after `v0.1.1`
* **Date:** 2026-11-16
* **Deciders:** Project maintainers

## Context and Problem Statement

After sign-in arrived ([ADR-0010](0010-session-sign-in-and-roles.md)), four kinds of action still needed a terminal:
injecting simulator faults (`curl` to an open nginx route), maintenance mode, restart and switchover (`snmpset` in a
throwaway container), searching past alarms, and changing an account's role. Every action a person takes must have a
screen, and every rule a screen applies must hold on the server too. How should these actions reach the equipment,
the simulator and the accounts?

## Decision Drivers

* **One sign-in and one set of role rules** for everything a person can do, enforced by the server.
* **An audit trail** of every command sent to a unit: who, what, and what came of it.
* **No training fault can reach a live centre:** the simulator console must not exist outside demo mode.
* **A change to an account takes effect at once,** not at the user's next request.

## Considered Options

1. **Each screen calls its target directly:** the browser calls the simulator through nginx, and a small sidecar
   sends SNMP SETs.
2. **Everything goes through the supervisor:** it sends SETs itself as the `operator` user, forwards the simulator
   console in demo mode, and administers the accounts.

## Decision Outcome

Chosen option: **Option 2.**

* **Equipment control:**
  * `POST /api/v1/equipment/{id}/commands` (SUPERVISOR) sends `MAINTENANCE_ON`, `MAINTENANCE_OFF`, `RESTART` or
    `MAKE_ACTIVE` as one SET of a writable `DEMO-ATM-TECHSUP-MIB` object, with the read-write `operator` SNMPv3 user
    that Compose already configures.
  * The agent decides whether a command fits the unit's state; `inconsistentValue` becomes 409, and a command that
    cannot be sent (for example, wrong operator credentials) 502.
  * A SET is never retried: a retry can reach a unit that already obeyed, which would refuse it and make an executed
    command look refused. No answer is therefore an uncertain outcome, 504 "the command may or may not have been
    applied".
  * [`EquipmentControl`] records every command, accepted or not, as an `EQUIPMENT_COMMANDED` event through the
    single board writer, with the username and the outcome.
* **Simulator console:** [`SimulatorConsoleController`] exists only with `atm.demo-mode=true`. It forwards
  `/api/v1/simulator/**` to the simulator's console API for supervisors, and nginx no longer exposes the simulator.
* **Accounts:** `/api/v1/admin/users` (ADMIN) changes roles, enables and disables accounts, and sets passwords. An
  administrator cannot change their own account there. [`LiveSessions`] ends every session of the changed account,
  and each ended session closes its live streams, so the user's windows go to the sign-in screen at once.
* **Screens:** the board's detail panel gets a Control section with an inline confirm bar that names the unit and
  the effect. The header links Alarm history for everyone, Simulator for supervisors in demo mode, and Users for
  administrators.

### Consequences

* **Good:** the server enforces every role rule the screens apply, and the event log shows every command next to the
  alarms it caused.
* **Good:** the fault API is no longer reachable without signing in.
* **Trade-off:** the supervisor now holds write credentials for the equipment. They come from the environment like
  the read-only ones, and demo values are refused outside demo mode.
* **Trade-off:** a command waits on the request thread for the agent's answer, at most the SNMP timeout of 1.5 s.

## Verification

* [`EquipmentControlTest`] and [`CommandControllerTest`] — command values, outcomes, the audit event and the roles.
* [`SnmpClientIT`] — a SET as `operator` changes maintenance mode, and a refused SET carries its error status.
* [`SimulatorConsoleControllerTest`] and [`SimulatorConsoleAbsentTest`] — forwarding, refusals, an unreachable
  simulator, and no console outside demo mode.
* [`AdminUserServiceIT`], [`AdminUserControllerTest`] and [`LiveSessionsTest`] — account changes, the own-account
  rule, and ended sessions.
* [`SupervisorEndToEndIT`] — a supervisor puts a unit into maintenance and the command appears in its events; signing
  out ends the open stream.

[`EquipmentControl`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/control/EquipmentControl.java
[`SimulatorConsoleController`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/simconsole/SimulatorConsoleController.java
[`LiveSessions`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/security/LiveSessions.java
[`EquipmentControlTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/control/EquipmentControlTest.java
[`CommandControllerTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/api/CommandControllerTest.java
[`SnmpClientIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/snmp/SnmpClientIT.java
[`SimulatorConsoleControllerTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/simconsole/SimulatorConsoleControllerTest.java
[`SimulatorConsoleAbsentTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/simconsole/SimulatorConsoleAbsentTest.java
[`AdminUserServiceIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/security/AdminUserServiceIT.java
[`AdminUserControllerTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/security/AdminUserControllerTest.java
[`LiveSessionsTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/security/LiveSessionsTest.java
[`SupervisorEndToEndIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/SupervisorEndToEndIT.java
