#!/bin/sh
# Lints DEMO-ATM-TECHSUP-MIB.txt with smilint (libsmi). Uses a local smilint if present,
# otherwise runs it in a throwaway Docker container. Exits non-zero if smilint prints anything
# or if the tool itself cannot run.
#
# Note: the Docker fallback also installs snmp-mibs-downloader, because smitools alone ships
# no standard MIBs (SNMPv2-SMI, SNMPv2-TC, SNMPv2-CONF) and smilint cannot resolve the imports.
set -u
cd "$(dirname "$0")"

rc=0
if command -v smilint >/dev/null 2>&1; then
    out=$(smilint -l 3 -s DEMO-ATM-TECHSUP-MIB.txt 2>&1) || rc=$?
else
    out=$(docker run --rm -q -v "$PWD":/m -w /m ubuntu:24.04 sh -c \
        "apt-get update -qq >/dev/null 2>&1 && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq smitools snmp-mibs-downloader >/dev/null 2>&1 && smilint -l 3 -s DEMO-ATM-TECHSUP-MIB.txt 2>&1") || rc=$?
fi

if [ -n "$out" ]; then
    printf '%s\n' "$out"
    exit 1
fi
exit "$rc"
