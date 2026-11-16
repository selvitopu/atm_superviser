#!/usr/bin/env bash
# Prepares and starts the ATM Supervisor demo, then waits until the board is READY.
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/demo-preflight.sh [--build] [--fresh] [--help]

  --build   Build the three images and pull postgres:18 (needs the network).
  --fresh   Remove the demo volumes first (alarm history, engine boots).
  --help    Show this help.

Exit status: 0 when READY, 1 otherwise. Honours COMPOSE_PROJECT_NAME.
USAGE
}

build=0
fresh=0
for arg in "$@"; do
  case "$arg" in
    --build) build=1 ;;
    --fresh) fresh=1 ;;
    --help) usage; exit 0 ;;
    *) usage; exit 1 ;;
  esac
done

cd "$(dirname "${BASH_SOURCE[0]}")/.."

if ! docker info >/dev/null 2>&1; then
  echo "Docker is not running."
  exit 1
fi

if [ "$build" -eq 1 ]; then
  docker compose build
  docker pull postgres:18
else
  missing=0
  while read -r image; do
    [ -n "$image" ] || continue
    if ! docker image inspect "$image" >/dev/null 2>&1; then
      missing=1
    fi
  done < <(docker compose config --images)
  if [ "$missing" -eq 1 ]; then
    echo "Images missing; run once while online: scripts/demo-preflight.sh --build"
    exit 1
  fi
fi

if [ "$fresh" -eq 1 ]; then
  echo "Removing demo volumes (alarm history, engine boots)."
  docker compose down -v
fi

if [ -z "$(docker compose ps --status running -q)" ] \
    && lsof -nP -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Port 8080 is in use by another process."
  exit 1
fi

if ! docker compose up -d --wait; then
  echo "The demo stack did not become healthy."
  docker compose ps || true
  docker compose logs --tail 50 supervisor || true
  exit 1
fi

# Resetting the simulator and reading the board need a signed-in supervisor. The demo supervisor exists because
# Compose runs in demo mode. The CSRF cookie comes with any response, so the sign-in fetches its settings first.
jar=$(mktemp)
trap 'rm -f "$jar"' EXIT

csrf_token() {
  awk '$6 == "XSRF-TOKEN" { print $7 }' "$jar"
}

sign_in() {
  curl -fsS --max-time 5 -c "$jar" -b "$jar" -o /dev/null localhost:8080/api/v1/auth/config || return 1
  curl -fsS --max-time 5 -c "$jar" -b "$jar" -o /dev/null -H "X-XSRF-TOKEN: $(csrf_token)" \
    --data-urlencode username=supervisor1 --data-urlencode password=supervisor1-demo \
    localhost:8080/api/v1/auth/login
}

reset_simulator() {
  curl -s --max-time 10 -c "$jar" -b "$jar" -o /dev/null -w '%{http_code}' -X POST \
    -H "X-XSRF-TOKEN: $(csrf_token)" localhost:8080/api/v1/simulator/reset || true
}

code=$(sign_in && reset_simulator)
if [ "$code" != "204" ]; then
  echo "Simulator reset failed (HTTP ${code:-no answer})."
  exit 1
fi

fetch_board() {
  curl -fsS --max-time 5 -b "$jar" localhost:8080/api/v1/board 2>/dev/null
}

evaluate() {
  python3 -c '
import json, sys
b = json.load(sys.stdin)
eq = b.get("equipment", [])
svc = b.get("services", [])
healths = ["%s=%s" % (e.get("id"), e.get("health")) for e in eq] + \
          ["%s=%s" % (s.get("id"), s.get("status")) for s in svc]
print(" ".join(healths))
ok = len(eq) == 7 and all(e.get("health") == "OPERATIONAL" for e in eq) \
     and svc and all(s.get("status") == "OK" for s in svc)
sys.exit(0 if ok else 1)
'
}

deadline=$((SECONDS + 120))
last=""
while [ "$SECONDS" -lt "$deadline" ]; do
  if board=$(fetch_board || { sign_in && fetch_board; }) \
      && last=$(printf '%s' "$board" | evaluate 2>/dev/null); then
    echo "READY  7/7 OPERATIONAL  SURV OK  FD OK  http://localhost:8080"
    exit 0
  fi
  [ -n "${board:-}" ] && last=$(printf '%s' "$board" | evaluate 2>/dev/null || true)
  sleep 2
done

echo "Not ready after 120 s. Current healths: ${last:-no board response}"
docker compose logs supervisor
exit 1
