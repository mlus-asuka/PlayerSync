#!/usr/bin/env bash
# Runs the PlayerSync end-to-end test:
#   shared MariaDB + two Forge 1.20.1 servers (docker) + a protocol bot (node).
#
# Usage: ./e2e/run-e2e.sh            (build the mod first: ./gradlew build)
#   KEEP=1 ./e2e/run-e2e.sh          keeps the environment running on failure for debugging
set -euo pipefail
cd "$(dirname "$0")"

# Pick the newest mod jar, ignoring sources/javadoc classifier jars. A version bump without
# `clean` leaves older jars in build/libs, and `ls | head` would grab the lexicographically-
# first (usually the stale one); warn if more than one real jar remains so a stale build
# can't be mounted silently.
mapfile -t JARS < <(ls -t ../build/libs/playersync-*.jar 2>/dev/null \
    | grep -Ev -- '-(sources|javadoc|dev)\.jar$' || true)
if (( ${#JARS[@]} == 0 )); then
    echo "Mod jar not found in build/libs — run ./gradlew build first." >&2
    exit 1
fi
JAR=${JARS[0]}
if (( ${#JARS[@]} > 1 )); then
    echo "warning: ${#JARS[@]} mod jars in build/libs; using newest $(basename "${JAR}"). Run ./gradlew clean build to be sure." >&2
fi
PLAYERSYNC_JAR="$(cd "$(dirname "${JAR}")" && pwd)/$(basename "${JAR}")"
export PLAYERSYNC_JAR
echo "Using mod jar: ${PLAYERSYNC_JAR}"

# Find a node runtime: PATH first, nix-shell as fallback (e.g. on NixOS).
if command -v node >/dev/null 2>&1; then
    run_node() { (cd bot && npm install --no-audit --no-fund && node test.js); }
elif command -v nix-shell >/dev/null 2>&1; then
    run_node() { nix-shell -p nodejs --run "cd bot && npm install --no-audit --no-fund && node test.js"; }
else
    echo "Neither node nor nix-shell found on PATH." >&2
    exit 1
fi

cleanup() {
    status=$?
    if [[ "${KEEP:-0}" == 1 && ${status} -ne 0 ]]; then
        echo "KEEP=1: leaving environment running. Tear down with: docker compose -f e2e/docker-compose.yml down -v"
    else
        docker compose down -v --remove-orphans
    fi
}
trap cleanup EXIT

echo "Starting database and both Forge servers (first run downloads Forge — takes a few minutes)..."
docker compose up -d --wait db server-a server-b

echo "Servers healthy, running bot test..."
run_node

echo "E2E PASSED"
