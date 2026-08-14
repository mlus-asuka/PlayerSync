#!/usr/bin/env bash
# Runs the PlayerSync end-to-end test:
#   shared MariaDB + two Forge 1.20.1 servers (docker) + a protocol bot (node).
#
# Usage: ./e2e/run-e2e.sh            (build the mod first: ./gradlew build)
#   KEEP=1 ./e2e/run-e2e.sh          keeps the environment running on failure for debugging
set -euo pipefail
cd "$(dirname "$0")"

# Pick the newest mod jar, ignoring sources/javadoc classifier jars. A version bump without
# `clean` leaves older jars in build/libs, so sort by modification time and warn when more
# than one real jar remains, otherwise a stale build could be mounted silently.
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

# Scenarios run in order. Each is a node script in bot/ that exits 0 on pass.
SCENARIOS=(
    test-sync-across-servers.js
    test-already-online.js
    test-disconnect-during-sync.js
)

# Toolchain baseline: mineflayer and the harness scripts assume a modern Node.
NODE_MAJOR_MIN=18

if ! command -v node >/dev/null 2>&1; then
    echo "node not found on PATH; the bot needs Node >= ${NODE_MAJOR_MIN}." >&2
    exit 1
fi
node_major=$(node -p 'process.versions.node.split(".")[0]')
if (( node_major < NODE_MAJOR_MIN )); then
    echo "node $(node --version) is too old; the bot needs Node >= ${NODE_MAJOR_MIN}." >&2
    exit 1
fi
run_node() { (cd bot && npm ci --no-audit --no-fund && for s in "${SCENARIOS[@]}"; do echo "=== scenario: $s ==="; node "$s" || exit 1; done); }

cleanup() {
    status=$?
    # Accept any value except empty/0/false/no as KEEP being set, so that KEEP=true or
    # KEEP=yes also preserves a failed environment for debugging.
    case "${KEEP:-}" in
        ''|0|false|no) keep=0 ;;
        *) keep=1 ;;
    esac
    if [[ ${keep} -eq 1 && ${status} -ne 0 ]]; then
        echo "KEEP=${KEEP}: leaving environment running. Tear down with: docker compose -f e2e/docker-compose.yml down -v"
    else
        docker compose down -v --remove-orphans
    fi
}
trap cleanup EXIT

echo "Starting database and both Forge servers (first run builds the image and downloads Forge — takes a few minutes)..."
# Recreate from a clean slate every run. --build picks up Dockerfile changes and is a cheap
# no-op when the image is cached. --force-recreate reloads a rebuilt jar even when its
# filename is unchanged, which a stack kept alive by KEEP would otherwise keep serving from
# the old container. --renew-anon-volumes discards world and player state from earlier runs.
docker compose up -d --wait --build --force-recreate --renew-anon-volumes db toxiproxy server-a server-b

echo "Servers healthy, running bot test..."
run_node

echo "E2E PASSED"
