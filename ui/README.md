# ATM Supervisor UI

The operator board of ATM Supervisor, in Angular 22: services, equipment tiles, a detail panel and the alarm list,
fed by the supervisor's REST API and its Server-Sent Events stream. See the [root README](../README.md) for the
project, the Docker Compose stack and how to inject faults.

## Development

Requires Node 26. Install the dependencies once with `npm ci`.

```sh
npx ng serve              # http://localhost:4200, proxying /api to localhost:8080
npx ng test --watch=false # unit tests
npx ng build              # production build into dist/atm-supervisor-ui/browser
```

`npx ng serve` needs a supervisor on port 8080 (see "Run without Docker" in the root README); the proxy is configured
in `proxy.conf.json`. The Simulator screen also needs the simulator, which the supervisor itself calls on port 8090 in
demo mode: the browser never talks to the simulator.
