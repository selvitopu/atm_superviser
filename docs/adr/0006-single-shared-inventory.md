# ADR-0006: Single Shared Device Inventory (`config/inventory.yml`)

* **Status:** Accepted — Implemented in `v0.1.1`
* **Date:** 2026-11-13 (revised 2026-11-15 for `v0.1.1`)
* **Deciders:** Project maintainers

## Context and Problem Statement

Both the multi-agent `simulator` and the `supervisor` must agree on the exact list of supervised devices, their equipment types, display names, operational groups, UDP ports (`16101–16107` in `v0.1.1`, `16101–16115` in `v0.2`), poll intervals, trap target address/port (`10162`), redundant pair partners (`SDPS-A` $\leftrightarrow$ `SDPS-B`, `FDPS-A` $\leftrightarrow$ `FDPS-B`), and SNMPv3 USM credentials.

Maintaining separate configuration files in `simulator/` and `supervisor/` invites silent drift (such as mismatched UDP ports, asymmetric pair links, or mismatched USM engine IDs).

## Decision Drivers

* **Single source of truth:** Device metadata and pair topology must be defined once and validated identically by both applications.
* **Fail-fast startup validation:** Invalid device IDs, duplicate UDP ports, broken pair partner references, or built-in demo passwords used outside demo mode must abort application startup immediately with a clear error message.

## Considered Options

1. **A single shared [`config/inventory.yml`] file parsed and validated by a dedicated `inventory` Maven module ([`Inventory`], [`InventoryProperties`], [`Device`])** *(Chosen)*
2. **Duplicated Spring `application.yml` device lists in `simulator` and `supervisor`**
3. **Database-seeded inventory with dynamic discovery** (unnecessary complexity for a fixed ACC estate where equipment additions occur only through engineering change requests)

## Decision Outcome

Chosen option: **Option 1.**

### How It Works in `v0.1.1`

* [`config/inventory.yml`] defines the centre name (`Demo ACC`), default host, default poll interval (`5s`), trap target (`port: 10162`), the device entries, and the `monitor` / `operator` SNMP credentials (expandable from environment variables `SNMP_MONITOR_AUTH`, `SNMP_MONITOR_PRIV`, `SNMP_OPERATOR_AUTH`, `SNMP_OPERATOR_PRIV`).
* The shared `inventory` Maven module ([`InventoryProperties`], [`Inventory`], [`Device`], [`EngineIds`], [`EngineBootsStore`]) validates the file at startup:
  * Rejects duplicate device IDs or duplicate `(host, port)` endpoints.
  * Verifies that every `pair` reference is symmetric and pairs two devices of the same [`DeviceType`].
  * Derives each device's fixed RFC 3411 SNMPv3 engine ID deterministically via [`EngineIds`].
* Both `simulator` and `supervisor` load [`config/inventory.yml`] at startup (or the path given by `ATM_INVENTORY`).

### Consequences

* **Good:** Zero configuration drift between the simulated agents, the supervisor's poll plans/pair logic, and integration test fixtures ([`TestInventories`]).
* **Good:** Adding the remaining `v0.2` device types (`ADSB`, `VCS`, `RADIO`, `RECORDER`, `SWITCH`) requires only adding their entries to [`config/inventory.yml`] and their device model classes.
* **Bad / Trade-off:** When running outside Docker from arbitrary working directories, `ATM_INVENTORY` must point to `config/inventory.yml` if `../config/inventory.yml` is not relative to the current directory.

## Verification

* [`InventoryTest`] & [`EngineIdsTest`] — Verify YAML loading, pair symmetry validation, duplicate port/ID rejection, and deterministic RFC 3411 engine ID derivation.
* [`SupervisorConfigTest`] & [`SimulatorConfigTest`] — Verify fail-fast rejection of demo SNMP credentials when `atm.demo-mode=false`.

[config/inventory.yml]: ../../config/inventory.yml
[Inventory]: ../../inventory/src/main/java/io/github/selvitopu/atmsup/inventory/Inventory.java
[InventoryProperties]: ../../inventory/src/main/java/io/github/selvitopu/atmsup/inventory/InventoryProperties.java
[Device]: ../../inventory/src/main/java/io/github/selvitopu/atmsup/inventory/Device.java
[DeviceType]: ../../inventory/src/main/java/io/github/selvitopu/atmsup/inventory/DeviceType.java
[EngineIds]: ../../inventory/src/main/java/io/github/selvitopu/atmsup/inventory/EngineIds.java
[EngineBootsStore]: ../../inventory/src/main/java/io/github/selvitopu/atmsup/inventory/EngineBootsStore.java
[TestInventories]: ../../simulator/src/test/java/io/github/selvitopu/atmsup/simulator/fixture/TestInventories.java
[InventoryTest]: ../../inventory/src/test/java/io/github/selvitopu/atmsup/inventory/InventoryTest.java
[EngineIdsTest]: ../../inventory/src/test/java/io/github/selvitopu/atmsup/inventory/EngineIdsTest.java
[SupervisorConfigTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/SupervisorConfigTest.java
[SimulatorConfigTest]: ../../simulator/src/test/java/io/github/selvitopu/atmsup/simulator/SimulatorConfigTest.java
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
