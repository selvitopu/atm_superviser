# ADR-0002: SNMPv3 `authPriv` by Default, Persistent `snmpEngineBoots`, and SNMPv2c (`IF-MIB`) for Legacy Switches

- **Status:** Accepted (SNMPv3 `authPriv` and persistent `snmpEngineBoots` implemented; SNMPv2c `IF-MIB` switches
  planned for `v0.2`)
- **Date:** 2026-11-13
- **Deciders:** Project maintainers

## Context and Problem Statement

Technical supervision in an Area Control Centre must authenticate and encrypt management traffic so equipment status
cannot be forged and control commands (`SET`) cannot be injected or replayed. At the same time:

1. Using SNMPv3 `TRAP` and `INFORM` notifications across independent agents requires deterministic engine IDs and
   RFC 3414 timeliness checks that survive agent restarts.
2. Real centres also contain legacy network equipment (such as operations LAN switches) that only expose standard
   `IF-MIB` over `SNMPv2c`.

## Decision Drivers

- **Authenticated and encrypted polling and control:** refuse unauthorized `SET` commands and unauthenticated
  notifications.
- **RFC 3414 restart behaviour ([Hazard H10](../safety/hazard-log.md#h10-v3-traps-rejected-after-an-agent-restart)):**
  if an SNMPv3 agent restarts with `snmpEngineBoots` reset or lower than before, the manager's USM time-window check
  drops all subsequent v3 traps from that agent as replays.
- **Small, readable agents:** avoid SNMP4J-Agent framework boilerplate for read-only tables and two writable scalars.

## Considered Options

1. **Hand-written SNMP4J core agents ([`DeviceAgent`], [`DeviceResponder`], [`MibView`]) using SNMPv3 USM `authPriv`
   (`SHA-256`/`AES-128`), deterministic engine IDs ([`EngineIds`]), and a persistent [`EngineBootsStore`], plus
   SNMPv2c `IF-MIB` for the planned LAN switches** (chosen).
2. **SNMPv2c everywhere** (simpler, but skips USM and engine-boot handling).
3. **Full SNMP4J-Agent framework with VACM MIB tables** (heavy boilerplate for tables populated by internal domain
   models rather than `RowStatus` writes).

## Decision Outcome

Chosen option: **Option 1.**

- **SNMPv3 USM `authPriv` (`SHA-256` and `AES-128`):**
  - Configured in [`SnmpSession`] (supervisor) and [`DeviceAgent`] (simulator).
  - Two USM users with separate credentials in [`SnmpProperties`]:
    - `monitor`: read-only (`GET`, `GETNEXT`, `GETBULK`) and notification sender.
    - `operator`: read access plus `SET` on `atmTsEqMaintenanceMode` and `atmTsEqCommand`, enforced in
      [`DeviceResponder`] (which rejects a `SET` from `monitor` with `noAccess`).
  - Outside demo mode (`atm.demo-mode=false`), [`SupervisorConfigTest`] and [`SimulatorConfigTest`] check that both
    applications refuse to start with the demo passwords.
- **Deterministic engine IDs and persisted `snmpEngineBoots`:**
  - [`EngineIds`] derives a fixed RFC 3411 engine ID from each device ID (`80:00:7e:d9:04:<ascii-id>`), so
    [`SnmpSession`] can localise USM keys for every known agent at startup.
  - [`EngineBootsStore`] persists an ever-growing boot counter per device in `engine-boots.json` on a Docker volume
    and increments it on every simulator start and simulated device `restart`, resetting `snmpEngineTime` and
    `sysUpTime` together.
- **Atomic multi-binding `SET` and copy-on-write [`MibView`]:**
  - Each device's MIB tree is an immutable `NavigableMap<OID, Variable>` in [`MibView`], swapped atomically on state
    changes.
  - [`DeviceResponder`] checks all varbinds of a `SET` and applies them under a single [`SetHandler`].`atomically(...)`
    lock in [`Centre`], publishing the new [`MibView`] once after the last binding.
- **SNMPv2c switches (`v0.2`):**
  - The two planned LAN switches (`LAN-A`, `LAN-B`) will use `SNMPv2c` and standard `IF-MIB` (`ifTable`, `ifXTable`,
    `linkDown`/`linkUp`).

### Consequences

- **Good:** SNMPv3 traps and informs after an agent restart are accepted by the supervisor's USM time window
  ([Hazard H10](../safety/hazard-log.md#h10-v3-traps-rejected-after-an-agent-restart)).
- **Good:** concurrent polls never observe a half-applied multi-varbind `SET`
  ([Hazard H6](../safety/hazard-log.md#h6-partial-or-concurrent-snmp-set)).
- **Trade-off:** `SHA-256` requires `net-snmp` 5.8 or newer when running `snmpget` or `snmpbulkwalk` from the host.

## Verification

- [`EngineBootsPersistenceIT`]: `trapAfterRestartWithHigherBootsIsAccepted` and
  `trapAfterRestartWithLowerBootsIsRejected`.
- [`DeviceAgentSetIT`] and [`CentreTest`]: USM `noAccess` for `monitor`,
  `notWritable`/`wrongType`/`wrongValue`/`inconsistentValue` ordering, and single-lock atomic `SET` publication.
- [`SnmpClientIT`] and [`TrapReceiverIT`]: `GET`, `GETBULK`, wrong-password USM report failures, and `TRAP`/`INFORM`
  delivery over UDP sockets.

[`CentreTest`]: ../../simulator/src/test/java/io/github/selvitopu/atmsup/simulator/centre/CentreTest.java
[`Centre`]: ../../simulator/src/main/java/io/github/selvitopu/atmsup/simulator/centre/Centre.java
[`DeviceAgentSetIT`]: ../../simulator/src/test/java/io/github/selvitopu/atmsup/simulator/agent/DeviceAgentSetIT.java
[`DeviceAgent`]: ../../simulator/src/main/java/io/github/selvitopu/atmsup/simulator/agent/DeviceAgent.java
[`DeviceResponder`]: ../../simulator/src/main/java/io/github/selvitopu/atmsup/simulator/agent/DeviceResponder.java
[`EngineBootsPersistenceIT`]: ../../simulator/src/test/java/io/github/selvitopu/atmsup/simulator/agent/EngineBootsPersistenceIT.java
[`EngineBootsStore`]: ../../inventory/src/main/java/io/github/selvitopu/atmsup/inventory/EngineBootsStore.java
[`EngineIds`]: ../../inventory/src/main/java/io/github/selvitopu/atmsup/inventory/EngineIds.java
[`MibView`]: ../../simulator/src/main/java/io/github/selvitopu/atmsup/simulator/agent/MibView.java
[`SetHandler`]: ../../simulator/src/main/java/io/github/selvitopu/atmsup/simulator/agent/SetHandler.java
[`SimulatorConfigTest`]: ../../simulator/src/test/java/io/github/selvitopu/atmsup/simulator/SimulatorConfigTest.java
[`SnmpClientIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/snmp/SnmpClientIT.java
[`SnmpProperties`]: ../../inventory/src/main/java/io/github/selvitopu/atmsup/inventory/SnmpProperties.java
[`SnmpSession`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/SnmpSession.java
[`SupervisorConfigTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/SupervisorConfigTest.java
[`TrapReceiverIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/snmp/TrapReceiverIT.java
