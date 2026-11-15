# ATM Supervisor

SNMP-based technical supervision for a simulated air traffic control centre. ATM Supervisor polls
a fleet of simulated surveillance and flight data devices over SNMPv3, receives their traps and
informs, raises and tracks alarms, derives the state of the operational services (surveillance and
flight data) and shows it all on a live board. The centre is fictional: **Demo ACC**.

## Status: v0.1

v0.1 is the first slice: three device types only, end to end. v0.1.1 hardens it and adds alarm-table
reconciliation (resync): every full poll walks the device's alarm table and corrects the alarms, so a
lost trap no longer hides a failure.

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

Not in v0.1 yet: supervisor control commands, security and login, snapshots, replay
and retention, Prometheus and Grafana, and the other device types (VCS, radio, recorder, LAN).
The roadmap from v0.2 to v1.0 is in [section 13 of the design](docs/design/2026-11-13-atm-supervisor-design.md).

## Known limitations (v0.1)

See [section 13 of the design](docs/design/2026-11-13-atm-supervisor-design.md) for the phases.

- **Service rules use availability only.** A DEGRADED unit counts as available (design §5.2), so a degraded
  active unit does not degrade its service. v0.2 widens the rules to equipment state.
- **Database outage.** The board keeps its last persisted state and the tiles turn amber-stale. There is no
  explicit storage-down signal yet, and the writer queue and the number of SSE clients are not yet bounded (v0.2).
- **Commands and replay arrive in v0.3.** That covers commands with tracking, idempotency and `UNCERTAIN`
  outcomes, and replay. `snmpset` from the CLI bypasses the supervisor.
- **Later phases.** The five scripted scenarios, the remaining device types, security, and Prometheus and
  Grafana come in later phases.

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

Requires Docker with Compose v2, bash, curl, python3 and lsof (the preflight script uses all of them). On
macOS, python3 comes with the Command Line Tools (`xcode-select --install`). The first time, while online,
build the images:

```sh
scripts/demo-preflight.sh --build
```

The script builds the images, starts the stack, resets the simulator and waits until the board is
`READY`. From then on the demo runs offline. Start it again from the repository root with:

```sh
docker compose up -d --wait
```

`--wait` returns once all four services are healthy. Open <http://localhost:8080>. The root
`compose.yaml` includes `deploy/docker-compose.yml`, so `docker compose -f deploy/docker-compose.yml up -d --wait`
is equivalent. `scripts/demo-preflight.sh --fresh` first removes the demo volumes (alarm history), and
`--help` lists the options. The scripted tour is in [docs/demo-v0.1.md](docs/demo-v0.1.md).

Inject a fault into the north radar, then undo it:

```sh
curl -X POST localhost:8080/sim/api/devices/RAD-N/faults -H 'Content-Type: application/json' -d '{"fault":"FAIL"}'
curl -X POST localhost:8080/sim/api/devices/RAD-N/faults -H 'Content-Type: application/json' -d '{"fault":"RECOVER"}'
```

Faults add up until `RECOVER` clears every fault of the device. Not every fault applies to every
device type; the simulator refuses the others with `400 Bad Request`:

| Fault | RADAR | SDPS | FDPS | Effect |
|---|:-:|:-:|:-:|---|
| `FAIL` | yes | yes | yes | The device fails (a radar loses both channels). |
| `DEGRADE` | yes | yes | yes | The device is degraded. |
| `RECOVER` | yes | yes | yes | Clears every fault of the device. |
| `CHANNEL_A_FAIL`, `CHANNEL_B_FAIL` | yes | | | One radar channel fails; the radar is degraded. |
| `SENSOR_LOST` | | yes | | One sensor input is lost. Needs a `target`. |
| `UNREACHABLE` | yes | yes | yes | The agent stops answering SNMP requests. |
| `DROP_TRAPS` | yes | yes | yes | The agent stops sending traps and informs. |

`SENSOR_LOST` needs a `target` naming the sensor, which is one of the radars `RAD-N`, `RAD-E` or
`RAD-S` (a second `SENSOR_LOST` replaces the first):

```sh
curl -X POST localhost:8080/sim/api/devices/SDPS-A/faults -H 'Content-Type: application/json' -d '{"fault":"SENSOR_LOST","target":"RAD-E"}'
```

`POST localhost:8080/sim/api/reset` clears all faults of all devices.

Stop the stack with `docker compose -f deploy/docker-compose.yml down` (add `-v` to drop the data volumes).

### Demo mode and ports

- Compose sets `ATM_DEMOMODE=true`. Outside demo mode the applications refuse the built-in demo SNMP passwords.
- Compose publishes ports on loopback only: `127.0.0.1:8080` (UI and, through nginx, the REST API) and
  `127.0.0.1:16101-16107/udp` (the SNMP agents).
- The SNMP passwords can be overridden with the environment variables `SNMP_MONITOR_AUTH`,
  `SNMP_MONITOR_PRIV`, `SNMP_OPERATOR_AUTH` and `SNMP_OPERATOR_PRIV`.

### Management port (Actuator)

The supervisor serves Actuator `health` and `metrics` on port 8081, bound to loopback inside its
container. Compose does not publish it. The runtime image has no `curl`, so ask from inside the
container with bash:

```sh
docker compose -f deploy/docker-compose.yml exec supervisor bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081 && printf "GET /actuator/metrics HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && cat <&3'
```

Replace `/actuator/metrics` with `/actuator/health`, or with a metric such as
`/actuator/metrics/atmsup.sse.clients`. When the supervisor runs outside Docker (see below), it is
simply <http://localhost:8081/actuator/metrics>.

### Talking to an agent with net-snmp

The agents use SHA-256 authentication, which needs **net-snmp 5.8 or later**. The `snmpget` that
ships with macOS is 5.6 and fails with `Invalid authentication protocol specified after -a flag:
SHA-256`. Use Homebrew's net-snmp (`brew install net-snmp`; it is keg-only, so call
`$(brew --prefix net-snmp)/bin/snmpget`) or a container.

With the stack up, read `sysUpTime` from the first agent (RAD-N, UDP 16101):

```sh
snmpget -v3 -l authPriv -u monitor -a SHA-256 -A monitor-auth-demo -x AES -X monitor-priv-demo localhost:16101 1.3.6.1.2.1.1.3.0
```

The same from a throwaway Alpine container on the Compose network, with nothing installed locally:

```sh
docker run --rm --network atm-supervisor_default alpine:3.22 sh -c 'apk add -q net-snmp-tools && snmpget -v3 -l authPriv -u monitor -a SHA-256 -A monitor-auth-demo -x AES -X monitor-priv-demo simulator:16101 1.3.6.1.2.1.1.3.0'
```

## Run without Docker

Stop the Compose stack first: it uses the same ports. You need JDK 25, Node 26 and a MySQL 8.4,
here in a container:

```sh
docker run -d --name atmsup-mysql -p 127.0.0.1:3306:3306 -e TZ=UTC -e MYSQL_DATABASE=atmsup -e MYSQL_USER=atmsup -e MYSQL_PASSWORD=atmsup-demo -e MYSQL_ROOT_PASSWORD=root-demo mysql:8.4
./mvnw -B -DskipTests package
```

Then start each application from its module directory, in its own terminal. The default inventory
path, `../config/inventory.yml`, and the default data directory, `./data`, are relative to the
working directory; from anywhere else, set `ATM_INVENTORY` to the inventory file and give each
application its own data directory (`ATM_SIMULATOR_DATA_DIR`, `ATM_SUPERVISOR_DATA_DIR`).

```sh
cd simulator && java -jar target/*-exec.jar --atm.demo-mode=true    # SNMP agents on UDP 16101-16107, fault API on :8090
cd supervisor && java -jar target/*-exec.jar --atm.demo-mode=true   # REST and SSE on :8080, Actuator on :8081
cd ui && npm ci && npx ng serve                                     # http://localhost:4200
```

`ng serve` proxies `/api` to the supervisor on 8080 and `/sim/api` to the simulator on 8090, so the
fault commands above work with `localhost:4200` in place of `localhost:8080`. Remove the database with
`docker rm -f atmsup-mysql`.

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
