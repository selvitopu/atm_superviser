# ADR-0006: Single Shared Device Inventory (`config/inventory.yml`)

- **Status:** Accepted (implemented)
- **Date:** 2026-11-13
- **Deciders:** Project maintainers

## Context and Problem Statement

Both the `simulator` and the `supervisor` must agree on the list of supervised devices, their equipment types, display
names, operational groups, UDP ports (`16101–16107` today, `16101–16115` in `v0.2`), poll intervals, trap target
address and port (`10162`), redundant pair partners (`SDPS-A` and `SDPS-B`, `FDPS-A` and `FDPS-B`), and SNMPv3 USM
credentials.

Keeping separate configuration files in `simulator/` and `supervisor/` invites configuration drift (such as mismatched
UDP ports, one-way pair links, or different USM engine IDs).

## Decision Drivers

- **One source of truth:** device metadata and pair topology should be defined once and validated the same way by both
  applications.
- **Fail-fast startup checks:** invalid device IDs, duplicate UDP ports, broken pair partner references, or built-in
  demo passwords used outside demo mode must stop startup at once with a clear error message.

## Considered Options

1. **One shared [`config/inventory.yml`] file parsed and checked by an `inventory` Maven module ([`Inventory`],
   [`InventoryProperties`], [`Device`])** (chosen).
2. **Separate Spring `application.yml` device lists in `simulator` and `supervisor`.**
3. **A database-seeded inventory with dynamic discovery** (unnecessary for a fixed centre where equipment changes go
   through engineering change requests).

## Decision Outcome

Chosen option: **Option 1.**

- [`config/inventory.yml`] defines the centre name (`Demo ACC`), default host, default poll interval (`5s`), trap
  target (`port: 10162`), the device entries, and the `monitor` and `operator` SNMP credentials (read from environment
  variables `SNMP_MONITOR_AUTH`, `SNMP_MONITOR_PRIV`, `SNMP_OPERATOR_AUTH`, `SNMP_OPERATOR_PRIV`).
- The shared `inventory` Maven module ([`InventoryProperties`], [`Inventory`], [`Device`], [`EngineIds`],
  [`EngineBootsStore`]) checks the file at startup:
  - Rejects duplicate device IDs or duplicate `(host, port)` endpoints.
  - Checks that every `pair` reference is symmetric and pairs two devices of the same [`DeviceType`].
  - Derives each device's fixed RFC 3411 SNMPv3 engine ID through [`EngineIds`].
- Both `simulator` and `supervisor` load [`config/inventory.yml`] at startup (or the path in `ATM_INVENTORY`).

### Consequences

- **Good:** no configuration drift between the simulated agents, the supervisor's poll plans and pair rules, and the
  test fixtures ([`TestInventories`]).
- **Good:** adding the remaining `v0.2` device types (`ADSB`, `VCS`, `RADIO`, `RECORDER`, `SWITCH`) only needs their
  entries in [`config/inventory.yml`] and their device model classes.
- **Trade-off:** when running outside Docker from another working directory, `ATM_INVENTORY` must point to
  `config/inventory.yml` if `../config/inventory.yml` is not relative to the current directory.

## Verification

- [`InventoryTest`] and [`EngineIdsTest`]: YAML loading, pair symmetry checks, duplicate port and ID rejection, and
  RFC 3411 engine ID derivation.
- [`SupervisorConfigTest`] and [`SimulatorConfigTest`]: startup refusal of demo SNMP credentials when
  `atm.demo-mode=false`.

[`DeviceType`]: ../../inventory/src/main/java/io/github/selvitopu/atmsup/inventory/DeviceType.java
[`Device`]: ../../inventory/src/main/java/io/github/selvitopu/atmsup/inventory/Device.java
[`EngineBootsStore`]: ../../inventory/src/main/java/io/github/selvitopu/atmsup/inventory/EngineBootsStore.java
[`EngineIdsTest`]: ../../inventory/src/test/java/io/github/selvitopu/atmsup/inventory/EngineIdsTest.java
[`EngineIds`]: ../../inventory/src/main/java/io/github/selvitopu/atmsup/inventory/EngineIds.java
[`InventoryProperties`]: ../../inventory/src/main/java/io/github/selvitopu/atmsup/inventory/InventoryProperties.java
[`InventoryTest`]: ../../inventory/src/test/java/io/github/selvitopu/atmsup/inventory/InventoryTest.java
[`Inventory`]: ../../inventory/src/main/java/io/github/selvitopu/atmsup/inventory/Inventory.java
[`SimulatorConfigTest`]: ../../simulator/src/test/java/io/github/selvitopu/atmsup/simulator/SimulatorConfigTest.java
[`SupervisorConfigTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/SupervisorConfigTest.java
[`TestInventories`]: ../../simulator/src/test/java/io/github/selvitopu/atmsup/simulator/fixture/TestInventories.java
[`config/inventory.yml`]: ../../config/inventory.yml
