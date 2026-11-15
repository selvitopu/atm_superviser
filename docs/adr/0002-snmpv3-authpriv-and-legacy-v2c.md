# ADR-0002: SNMPv3 `authPriv` by Default, Persistent `snmpEngineBoots`, and SNMPv2c (`IF-MIB`) for Legacy Switches

* **Status:** Accepted — SNMPv3 `authPriv` & persistent `snmpEngineBoots` implemented in `v0.1.1`; SNMPv2c `IF-MIB` switches planned for `v0.2`
* **Date:** 2026-11-13 (revised 2026-11-15 for `v0.1.1`)
* **Deciders:** Project maintainers

## Context and Problem Statement

Technical supervision in an Area Control Centre must authenticate and encrypt management traffic so equipment status cannot be forged and control commands (`SET`) cannot be injected or replayed. At the same time:
1. Using SNMPv3 `TRAP` and `INFORM` notifications across multiple independent agents requires deterministic engine ID discovery and strict compliance with RFC 3414 timeliness checks across agent restarts.
2. Real ACC environments contain legacy network infrastructure (such as operations LAN switches) that only expose standard `IF-MIB` over `SNMPv2c`.

## Decision Drivers

* **Authentic and confidential telemetry/control:** Prevent unauthorized `SET` mutations and unauthenticated notifications.
* **RFC 3414 restart resilience (Hazard H10):** If an SNMPv3 agent restarts with `snmpEngineBoots` reset or lower than before, the manager's USM time-window check silently drops all subsequent v3 traps from that agent as replays.
* **Explainable agent implementation:** Avoid opaque SNMP4J-Agent framework boilerplate for read-only tables and two writable scalars.

## Considered Options

1. **Hand-written SNMP4J core agents ([`DeviceAgent`], [`DeviceResponder`], [`MibView`]) using SNMPv3 USM `authPriv` (`SHA-256`/`AES-128`), deterministic engine IDs ([`EngineIds`]), and persistent [`EngineBootsStore`], plus SNMPv2c `IF-MIB` for the planned LAN switches** *(Chosen)*
2. **SNMPv2c everywhere** (simpler, but fails modern CNS security baselines and hides USM/engine-boot engineering)
3. **Full SNMP4J-Agent framework with VACM MIB tables** (heavy boilerplate for read-only tables that are populated by internal domain models rather than `RowStatus` SNMP writes)

## Decision Outcome

Chosen option: **Option 1.**

### Key Protocol & Security Mechanisms Implemented in `v0.1.1`

1. **SNMPv3 USM `authPriv` (`SHA-256` + `AES-128`):**
   * Configured in [`SnmpSession`] (supervisor) and [`DeviceAgent`] (simulator).
   * Two USM users with separate credentials in [`SnmpProperties`]:
     * `monitor`: read-only (`GET`, `GETNEXT`, `GETBULK`) and notification sender.
     * `operator`: read access plus `SET` on `atmTsEqMaintenanceMode` and `atmTsEqCommand`, enforced in [`DeviceResponder`] (which rejects `SET` from `monitor` with `noAccess`).
   * Outside demo mode (`atm.demo-mode=false`), [`SupervisorConfigTest`] and [`SimulatorConfigTest`] verify that the applications refuse to start with the built-in demo passwords.
2. **Deterministic Engine IDs & Persisted `snmpEngineBoots`:**
   * [`EngineIds`] derives a fixed RFC 3411 engine ID from each device ID (`80:00:7e:d9:04:<ascii-id>`), allowing [`SnmpSession`] to localise USM keys for every known agent at startup.
   * [`EngineBootsStore`] persists an ever-growing boot counter per device in `engine-boots.json` on a Docker volume and increments it on every simulator start and simulated device `restart`, resetting `snmpEngineTime` and `sysUpTime` simultaneously.
3. **Atomic Multi-Binding `SET` & Copy-on-Write [`MibView`]:**
   * Each device's MIB tree is an immutable `NavigableMap<OID, Variable>` in [`MibView`], swapped atomically on state changes.
   * In `v0.1.1`, [`DeviceResponder`] validates all varbinds of a `SET` and applies them inside a single [`SetHandler`].`atomically(...)` lock in [`Centre`], publishing the updated [`MibView`] once after the final binding.
4. **Brownfield SNMPv2c Switches (`v0.2`):**
   * The two planned LAN switches (`LAN-A`, `LAN-B`) will deliberately use `SNMPv2c` and standard `IF-MIB` (`ifTable`, `ifXTable`, `linkDown`/`linkUp`) to demonstrate multi-protocol supervision.

### Consequences

* **Good:** Post-restart SNMPv3 traps and informs are always accepted by the supervisor's USM time window (mitigating [Hazard H10](../safety/hazard-log.md#h10-v3-traps-rejected-after-an-agent-restart)).
* **Good:** Concurrent polls can never observe a half-applied multi-varbind `SET` (mitigating [Hazard H6](../safety/hazard-log.md#h6-partial-or-concurrent-snmp-set)).
* **Bad / Trade-off:** `SHA-256` requires `net-snmp` 5.8+ when running CLI tools (`snmpget`, `snmpbulkwalk`) from the host.

## Verification

* [`EngineBootsPersistenceIT`] — Verifies `trapAfterRestartWithHigherBootsIsAccepted` and `trapAfterRestartWithLowerBootsIsRejected`.
* [`DeviceAgentSetIT`] & [`CentreTest`] — Verify USM `noAccess` for `monitor`, `notWritable`/`wrongType`/`wrongValue`/`inconsistentValue` ordering, and single-lock atomic `SET` publication.
* [`SnmpClientIT`] & [`TrapReceiverIT`] — Verify `GET`, `GETBULK`, wrong-password USM report failures, and `TRAP`/`INFORM` delivery over real UDP sockets.

[DeviceAgent]: ../../simulator/src/main/java/io/github/selvitopu/atmsup/simulator/agent/DeviceAgent.java
[DeviceResponder]: ../../simulator/src/main/java/io/github/selvitopu/atmsup/simulator/agent/DeviceResponder.java
[MibView]: ../../simulator/src/main/java/io/github/selvitopu/atmsup/simulator/agent/MibView.java
[SetHandler]: ../../simulator/src/main/java/io/github/selvitopu/atmsup/simulator/agent/SetHandler.java
[Centre]: ../../simulator/src/main/java/io/github/selvitopu/atmsup/simulator/centre/Centre.java
[EngineIds]: ../../inventory/src/main/java/io/github/selvitopu/atmsup/inventory/EngineIds.java
[EngineBootsStore]: ../../inventory/src/main/java/io/github/selvitopu/atmsup/inventory/EngineBootsStore.java
[SnmpProperties]: ../../inventory/src/main/java/io/github/selvitopu/atmsup/inventory/SnmpProperties.java
[SnmpSession]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/SnmpSession.java
[EngineBootsPersistenceIT]: ../../simulator/src/test/java/io/github/selvitopu/atmsup/simulator/agent/EngineBootsPersistenceIT.java
[DeviceAgentSetIT]: ../../simulator/src/test/java/io/github/selvitopu/atmsup/simulator/agent/DeviceAgentSetIT.java
[CentreTest]: ../../simulator/src/test/java/io/github/selvitopu/atmsup/simulator/centre/CentreTest.java
[SnmpClientIT]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/snmp/SnmpClientIT.java
[TrapReceiverIT]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/snmp/TrapReceiverIT.java
[SupervisorConfigTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/SupervisorConfigTest.java
[SimulatorConfigTest]: ../../simulator/src/test/java/io/github/selvitopu/atmsup/simulator/SimulatorConfigTest.java
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
[`SnmpSession`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/SnmpSession.java
[`SupervisorConfigTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/SupervisorConfigTest.java
[`TrapReceiverIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/snmp/TrapReceiverIT.java
