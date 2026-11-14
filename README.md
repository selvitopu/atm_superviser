# ATM Supervisor

SNMP-based technical supervision for a simulated air traffic control centre. ATM Supervisor polls
a fleet of simulated surveillance and flight data devices over SNMPv3, receives their traps and
informs, raises and tracks alarms, derives the state of the operational services (surveillance and
flight data) and shows it all on a live board. The centre is fictional: **Demo ACC**.

## Status: v0.1

v0.1 is the first slice: three device types only, end to end.

What works:

- 3 device types (RADAR, SDPS, FDPS) and 7 devices: RAD-N, RAD-E, RAD-S, SDPS-A/B, FDPS-A/B.
- Two services, SURV (surveillance) and FD (flight data), evaluated from device state.
- SNMPv3 authPriv (SHA-256 / AES-128) polling with lightweight probes and a circuit breaker per device.
- SNMPv3 traps and informs received on UDP 10162.
- Alarms with acknowledge.
- A live board in Angular, fed by Server-Sent Events (SSE) with resume.
- An event log with telemetry sampled every 30 s.
- A simulator with a fault-injection REST API.
- Actuator health and metrics on the management port (supervisor: 8081, loopback only).

Not in v0.1 yet: alarm resync, supervisor control commands, security and login, snapshots, replay
and retention, Prometheus and Grafana, and the other device types (VCS, radio, recorder, LAN).
The roadmap from v0.2 to v1.0 is in [section 13 of the design](docs/design/2026-11-13-atm-supervisor-design.md).

## Architecture

```
 simulator (7 SNMPv3 agents, UDP 16101-16107; fault API :8090)
     |  SNMPv3 polls (supervisor -> agent)      ^
     |  traps / informs (agent -> supervisor, UDP 10162)
     v                                          |
 supervisor (polling, alarms, services; REST + SSE :8080) ---- MySQL 8.4
     ^
     |  /api/  and  /api/v1/stream (SSE)
   nginx  --  serves the Angular UI;  /sim/api/ -> simulator fault API
     ^
 browser  http://localhost:8080
```

| Module | Purpose |
|---|---|
| `mib/` | `DEMO-ATM-TECHSUP-MIB.txt`, OID constants and `lint.sh` (smilint) |
| `inventory/` | Device inventory model, loaded from `config/inventory.yml` |
| `simulator/` | 7 SNMPv3 agents and a fault REST API |
| `supervisor/` | Polling, traps, alarms, board, REST and SSE, MySQL persistence |
| `ui/` | Angular 22 board |
| `deploy/` | `docker-compose.yml` and the nginx config |

## Quickstart

Requires Docker with Compose.

```sh
docker compose -f deploy/docker-compose.yml up -d --build
```

Open <http://localhost:8080>. The board fills in after a few seconds.

Inject a fault into the north radar, then undo it:

```sh
curl -X POST localhost:8080/sim/api/devices/RAD-N/faults -H 'Content-Type: application/json' -d '{"fault":"FAIL"}'
curl -X POST localhost:8080/sim/api/devices/RAD-N/faults -H 'Content-Type: application/json' -d '{"fault":"RECOVER"}'
```

Fault kinds: `FAIL`, `DEGRADE`, `RECOVER`, `CHANNEL_A_FAIL`, `CHANNEL_B_FAIL`, `SENSOR_LOST`,
`UNREACHABLE`, `DROP_TRAPS`. A request may also carry a `target`, naming the part concerned (for
example a sensor). `POST localhost:8080/sim/api/reset` clears all faults.

Stop the stack with `docker compose -f deploy/docker-compose.yml down` (add `-v` to drop the data volumes).

### Demo mode and ports

- Compose sets `ATM_DEMOMODE=true`. Outside demo mode the applications refuse the built-in demo SNMP passwords.
- Compose publishes ports on loopback only: `127.0.0.1:8080` (UI and, through nginx, the REST API) and
  `127.0.0.1:16101-16107/udp` (the SNMP agents).
- The SNMP passwords can be overridden with the environment variables `SNMP_MONITOR_AUTH`,
  `SNMP_MONITOR_PRIV`, `SNMP_OPERATOR_AUTH` and `SNMP_OPERATOR_PRIV`.

### Talking to an agent with net-snmp

With the stack up, read `sysUpTime` from the first agent (RAD-N, UDP 16101):

```sh
snmpget -v3 -l authPriv -u monitor -a SHA-256 -A monitor-auth-demo -x AES -X monitor-priv-demo localhost:16101 1.3.6.1.2.1.1.3.0
```

## Development

Requires JDK 25 (Temurin), Node 26 and Docker (the integration tests use Testcontainers).

```sh
./mvnw verify                                  # all backend modules and tests
cd ui && npm ci && npx ng test --watch=false   # UI unit tests
cd ui && npx ng build                          # UI production build
cd mib && ./lint.sh                            # lint the MIB with smilint (falls back to Docker)
```

CI (`.github/workflows/ci.yml`) runs the same three checks: backend, frontend and MIB lint.

## Documentation

- [Design specification](docs/design/2026-11-13-atm-supervisor-design.md)
- [Architecture review decisions](docs/refactor/ATM-Supervisor-Architecture-Review-Decisions.md): the binding
  amendment (circuit breaker, probes, single-flight polling, SSE backpressure, telemetry sampling)

## Licence

Apache-2.0. See [LICENSE](LICENSE).

## Disclaimer

Demo ACC is fictional. This project is not affiliated with any air navigation service provider.
