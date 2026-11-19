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
  * The agent decides whether a command fits the unit's state. Its `inconsistentValue` becomes 409 with the rule of
    that command. Any other error status (`noAccess`, `notWritable`, ...), wrong operator credentials, or a SET that
    never left the supervisor (an unresolved host) becomes 502, which points at the configuration and not at the
    unit's state.
  * A SET is never retried: a retry can reach a unit that already obeyed, which would refuse it and make an executed
    command look refused. No answer is therefore an uncertain outcome, 504 "the command may or may not have been
    applied".
  * [`EquipmentControl`] records every command through the single board writer, as `EQUIPMENT_COMMANDED` events
    with the username: its intent (`ISSUED`) before the SET, and its outcome after it.
  * The intent must be stored first. If it is not stored within 3 s (`RECORD_TIMEOUT`), because the database
    refused it, stalled, or the writer is stopping, nothing is sent: 503 "The command was not sent: it could not be
    recorded". `NOT_SENT` is then recorded after the intent, so an intent that commits late never reads as a command
    that was sent.
  * An outcome that cannot be stored within 3 s is still answered, because the unit has acted or not; it is logged
    as an error, and the intent remains the audit trail.
* **Simulator console:** [`SimulatorConsoleController`] exists only with `atm.demo-mode=true`. It forwards
  `/api/v1/simulator/**` to the simulator's console API for supervisors, and nginx no longer exposes the simulator.
* **Accounts:** `/api/v1/admin/users` (ADMIN) changes roles, enables and disables accounts, and sets passwords. An
  administrator cannot change their own account there. Once the change is committed, [`LiveSessions`] ends every
  session of the changed account. The server then refuses each of its requests, and each ended session closes its
  live streams, so a board returns to the sign-in screen within seconds; other screens follow at their next request.
* **Screens:** the board's detail panel gets a Control section with an inline confirm bar that names the unit and
  the effect. The header links Alarm history for everyone, Simulator for supervisors in demo mode, and Users for
  administrators.

### Consequences

* **Good:** the server enforces every role rule the screens apply, and the event log shows every command next to the
  alarms it caused.
* **Good:** the fault API is no longer reachable without signing in.
* **Trade-off:** the supervisor now holds write credentials for the equipment. They come from the environment like
  the read-only ones, and demo values are refused outside demo mode.
* **Trade-off:** a command waits on the request thread: at most 3 s to store its intent, the SNMP timeout of 1.5 s
  for the agent's answer, and 3 s to store its outcome, so 7.5 s in the worst case. A healthy database stores each
  record in milliseconds.

### Relation to ADR-0008

[ADR-0008](0008-independent-probes-two-generations-and-crash-safe-commands.md) §4 plans crash-safe commands for
`v0.3`. This decision adopts part of it now and leaves the rest planned:

* **Adopted now:** the intent is recorded first (`ISSUED`, ADR-0008's `COMMAND_ISSUED`), and a command whose intent
  cannot be stored is not sent; a SET is sent with 0 retries; and no answer is an uncertain outcome (504) that the
  supervisor is told to check before sending again.
* **Still planned for `v0.3`:** the `Idempotency-Key` with its request fingerprint; durable exclusion in
  `active_command` per unit, and per pair for a switchover; and an `UNCERTAIN` status reconciled by polling, which
  keeps the unit locked until polling confirms its state. Until then nothing stops two supervisors from commanding
  the same unit at once, and a person resolves an uncertain outcome by checking the unit.

## Verification

* [`EquipmentControlTest`] and [`CommandControllerTest`] — command values, the intent recorded before the SET, no SET
  when the intent is refused, times out or meets a stopped writer, the reason for each error status, and the roles.
* [`SnmpClientIT`] and [`Snmp4jClientTest`] — a SET as `operator` changes maintenance mode, a refused SET carries its
  error status, and a SET to a host that does not resolve is reported as never sent.
* [`SimulatorConsoleControllerTest`] and [`SimulatorConsoleAbsentTest`] — forwarding, refusals, an unreachable
  simulator, and no console outside demo mode.
* [`AdminUserServiceIT`], [`AdminUserControllerTest`] and [`LiveSessionsTest`] — account changes, the own-account
  rule, and sessions ended once the change is committed.
* [`SupervisorEndToEndIT`] — a supervisor puts a unit into maintenance and its events show the command issued, then
  done; signing out ends the open stream, and so does an administrator's change to the signed-in account, after which
  its session is refused.

[`EquipmentControl`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/control/EquipmentControl.java
[`SimulatorConsoleController`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/simconsole/SimulatorConsoleController.java
[`LiveSessions`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/security/LiveSessions.java
[`EquipmentControlTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/control/EquipmentControlTest.java
[`CommandControllerTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/api/CommandControllerTest.java
[`SnmpClientIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/snmp/SnmpClientIT.java
[`Snmp4jClientTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/snmp/Snmp4jClientTest.java
[`SimulatorConsoleControllerTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/simconsole/SimulatorConsoleControllerTest.java
[`SimulatorConsoleAbsentTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/simconsole/SimulatorConsoleAbsentTest.java
[`AdminUserServiceIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/security/AdminUserServiceIT.java
[`AdminUserControllerTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/security/AdminUserControllerTest.java
[`LiveSessionsTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/security/LiveSessionsTest.java
[`SupervisorEndToEndIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/SupervisorEndToEndIT.java
