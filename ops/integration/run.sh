#!/usr/bin/env bash
# Boots a throwaway economy stack and runs the live integration tests against it.
#
# The tests (integration-tests/ — its own Gradle build) drive a real Paper server
# running the plugins built from THIS checkout with a mock Minecraft client, RCON
# and direct MariaDB assertions. This script is the whole environment, so the same
# command works locally and in CI:
#
#   ops/integration/run.sh
#   ops/integration/run.sh -- --tests '*PayCommandTest'
#   bash ops/integration/run.sh --profile protection
#
# What it does: builds the plugin jars + REST API from this checkout and the
# DcDiag / Nexo-stub plugins from integration-tests/; downloads Paper, LuckPerms
# WorldEdit, WorldGuard and GriefPrevention (cached); migrates an empty database with economy-flyway;
# starts Paper (offline mode, RCON) and the REST API; runs `:tests:test`; stops
# everything. Nothing outside the work dir and the download cache is touched.
#
# Database: by default a scratch mariadbd is started on IT_DB_PORT (needs
# `mariadbd` + `mariadb-install-db` installed). Set IT_DB_EXTERNAL=1 to use an
# already-running server instead (CI service container) — the database named by
# IT_DB_NAME is DROPPED and recreated.
#
# Requires: JDK 21 on PATH/JAVA_HOME, curl and a mariadb/mysql client.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

TESTS_DIR="${IT_TESTS_DIR:-$ROOT/integration-tests}"
KEEP=0
PROFILE=core
RESTART_ENABLED=false
GROUPS_PROFILE=false
TAX_PROFILE=false
WEBHOOK_FAILURE_PROFILE=false
REST_PROFILE_ARGS=()
GRADLE_TASKS=(:tests:preflight :tests:test)
TEST_ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --tests-dir) TESTS_DIR="$2"; shift 2 ;;   # a different copy of the suite
    --keep) KEEP=1; shift ;;          # leave the stack running after the tests
    --profile) PROFILE="$2"; shift 2 ;; # core or protection (startup-only shop hooks)
    --) shift; TEST_ARGS=("$@"); break ;;
    -h|--help) sed -n '2,25p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
case "$PROFILE" in
  core) PROFILE_ARGS=() ;;
  groups)
    PROFILE_ARGS=()
    GROUPS_PROFILE=true
    if [ "${#TEST_ARGS[@]}" -eq 0 ]; then TEST_ARGS=(--tests '*DerivedGroupLifecycleTest'); fi
    ;;
  explorer)
    PROFILE_ARGS=()
    GRADLE_TASKS=(:tests:preflight :tests:explorerFixture)
    if [ "${#TEST_ARGS[@]}" -ne 0 ]; then echo "explorer profile does not accept Gradle test filters" >&2; exit 2; fi
    ;;
  webhook-failure)
    PROFILE_ARGS=()
    WEBHOOK_FAILURE_PROFILE=true
    REST_PROFILE_ARGS=(--treasury.webhook.max-attempts=3 --treasury.webhook.failure-threshold=2 --treasury.webhook.retry-base-seconds=1 --treasury.webhook.retry-max-seconds=2)
    if [ "${#TEST_ARGS[@]}" -eq 0 ]; then TEST_ARGS=(--tests '*WebhookExhaustionTest'); fi
    ;;
  tax)
    PROFILE_ARGS=()
    TAX_PROFILE=true
    if [ "${#TEST_ARGS[@]}" -eq 0 ]; then TEST_ARGS=(--tests '*TaxCycleLifecycleTest' --tests '*TaxConfigPublicationTest'); fi
    ;;
  restart)
    PROFILE_ARGS=()
    RESTART_ENABLED=true
    if [ "${#TEST_ARGS[@]}" -eq 0 ]; then TEST_ARGS=(--tests '*ServerRestartTest'); fi
    ;;
  protection)
    PROFILE_ARGS=(-Dchestshop.integrations=true)
    if [ "${#TEST_ARGS[@]}" -eq 0 ]; then TEST_ARGS=(--tests '*ProtectionIntegrationCreationTest'); fi
    ;;
  *) echo "unknown acceptance profile: $PROFILE (expected core, protection, restart, tax, groups, webhook-failure or explorer)" >&2; exit 2 ;;
esac
TESTS_DIR="$(cd "$TESTS_DIR" && pwd)"

WORK="${IT_WORK_DIR:-$ROOT/build/integration}"
CACHE="${IT_CACHE_DIR:-$HOME/.cache/hibernia-it}"
SERVER="$WORK/server"

MC_PORT="${IT_MC_PORT:-25565}"
RCON_PORT="${IT_RCON_PORT:-25575}"
RCON_PASSWORD="${IT_RCON_PASSWORD:-dev}"
REST_PORT="${IT_REST_PORT:-8080}"
# The tests run a local HTTP receiver here; the REST API is told it may deliver
# webhooks to it despite the SSRF guard (treasury.webhook.insecure-test-targets).
WEBHOOK_PORT="${IT_WEBHOOK_PORT:-8099}"
BYTEBIN_PORT="${IT_BYTEBIN_PORT:-8098}"
DB_EXTERNAL="${IT_DB_EXTERNAL:-0}"
DB_HOST="${IT_DB_HOST:-127.0.0.1}"
DB_PORT="${IT_DB_PORT:-33306}"
DB_NAME="${IT_DB_NAME:-economy}"
DB_USER="${IT_DB_USER:-root}"
DB_PASSWORD="${IT_DB_PASSWORD:-dev}"
# The tests forge tokens with this exact secret (support/JwtForge.java).
JWT_SECRET="${IT_JWT_SECRET:-dev-jwt-secret-change-in-production-please-use-32-plus-random-bytes}"

# Never mistake another stack's listener for this run's successful startup.
# External databases are intentionally shared; every other listener is owned here.
CHECK_PORTS=("$MC_PORT" "$RCON_PORT" "$REST_PORT" "$WEBHOOK_PORT" "$BYTEBIN_PORT")
if [ "$DB_EXTERNAL" != 1 ]; then CHECK_PORTS+=("$DB_PORT"); fi
python3 - "${CHECK_PORTS[@]}" <<'PY'
import socket
import sys

ports = [int(value) for value in sys.argv[1:]]
if len(set(ports)) != len(ports):
    raise SystemExit("integration listener ports must be distinct")
for port in ports:
    with socket.socket() as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            listener.bind(("127.0.0.1", port))
        except OSError as error:
            raise SystemExit(f"integration port {port} unavailable: {error}") from error
PY

# Pinned downloads. Paper is addressed by content hash; bump them together
# with the server version the plugins target.
PAPER_JAR="paper-1.21.11-132.jar"
PAPER_URL="https://fill-data.papermc.io/v1/objects/5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba/$PAPER_JAR"
PAPER_SHA256="5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba"
LUCKPERMS_JAR="LuckPerms-Bukkit-5.5.87.jar"
LUCKPERMS_URL="https://download.luckperms.net/1674/bukkit/loader/$LUCKPERMS_JAR"
GP_JAR="GriefPrevention-16.18.6.jar"
GP_URL="https://cdn.modrinth.com/data/O4o4mKaq/versions/4RqY8Kfl/GriefPrevention.jar"
# DcDiag links against WorldGuard, so it (and WorldEdit) must be installed even
# for runs that don't exercise the protection hooks. These are the last builds
# that run on Java 21 and support 1.21.11.
WE_JAR="worldedit-bukkit-7.4.2.jar"
WE_URL="https://cdn.modrinth.com/data/1u6JkXh5/versions/p8T2aZ8U/$WE_JAR"
WG_JAR="worldguard-bukkit-7.0.15.jar"
WG_URL="https://cdn.modrinth.com/data/DKY9btbd/versions/WaElxvDz/$WG_JAR"

log() { printf '\n==> %s\n' "$*"; }

fetch() { # fetch <url> <file> [sha256]
  local url="$1" file="$CACHE/$2" sha="${3:-}"
  if [ ! -s "$file" ]; then
    curl -fsSL -H "User-Agent: hibernia-economy-integration-tests" -o "$file.part" "$url"
    mv "$file.part" "$file"
  fi
  if [ -n "$sha" ]; then
    echo "$sha  $file" | sha256sum -c --quiet
  fi
}

PIDS=()
cleanup() {
  local code=$?
  if [ "$KEEP" = 1 ] && [ $code -eq 0 ]; then
    echo "--keep: stack left running (work dir $WORK); stop with: kill ${PIDS[*]:-}"
    return
  fi
  for pid in "${PIDS[@]:-}"; do [ -n "$pid" ] && kill "$pid" 2>/dev/null || true; done
  for pid in "${PIDS[@]:-}"; do [ -n "$pid" ] && wait "$pid" 2>/dev/null || true; done
  if [ $code -ne 0 ]; then
    echo "integration run failed (exit $code). Logs: $WORK/server.log $WORK/rest-api.log $WORK/mariadb.log" >&2
  fi
}
trap cleanup EXIT

wait_for_port() { # wait_for_port <port> <seconds> <what> <pid>
  local port="$1" deadline=$((SECONDS + $2))
  until (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; do
    kill -0 "$4" 2>/dev/null || { echo "$3 exited before listening on :$port" >&2; return 1; }
    [ $SECONDS -lt $deadline ] || { echo "$3 did not listen on :$port within $2s" >&2; return 1; }
    sleep 1
  done
}

mkdir -p "$CACHE"
rm -rf "$WORK"
mkdir -p "$SERVER/plugins"

# ── Downloads ────────────────────────────────────────────────────────────────
log "Fetching Paper and third-party plugins (cache: $CACHE)"
fetch "$PAPER_URL" "$PAPER_JAR" "$PAPER_SHA256"
fetch "$LUCKPERMS_URL" "$LUCKPERMS_JAR"
fetch "$GP_URL" "$GP_JAR"
fetch "$WE_URL" "$WE_JAR"
fetch "$WG_URL" "$WG_JAR"

# ── Build ────────────────────────────────────────────────────────────────────
if [ "${IT_SKIP_BUILD:-0}" = 1 ]; then
  log "IT_SKIP_BUILD=1: reusing the jars already in release/ and treasury-rest-api/build/libs"
else
  log "Building plugins and REST API from $ROOT"
  # IT_GRADLE_ARGS: extra arguments for this build, e.g. to resolve a locally
  # published hibernia-framework ("-PuseMavenLocal -Dmaven.repo.local=…").
  # shellcheck disable=SC2086
  (cd "$ROOT" && sh ./gradlew --console=plain -q release :treasury-rest-api:bootJar -Pci=true ${IT_GRADLE_ARGS:-})
fi

# Reject incomplete cached/staged artifacts before starting any services.
python3 - "$ROOT/release" <<'PYZIP'
from pathlib import Path
from zipfile import ZipFile
import sys
jars = list(Path(sys.argv[1]).glob('*.jar'))
if len(jars) != 4:
    raise SystemExit('Expected four release plugin JARs; rebuild with ./gradlew release --rerun')
for jar in jars:
    try:
        with ZipFile(jar) as archive:
            if archive.testzip() is not None:
                raise ValueError('entry CRC mismatch')
    except Exception as error:
        raise SystemExit(f'Invalid release artifact {jar.name}: {error}; rebuild with ./gradlew release --rerun')
PYZIP

log "Building DcDiag, Nexo stub and tests from $TESTS_DIR"
(cd "$ROOT" && sh ./gradlew -p "$TESTS_DIR" --console=plain -q :diag-plugin:jar :nexo-stub:jar :tests:testClasses \
    -PgriefPreventionJar="$CACHE/$GP_JAR")

# ── Database ─────────────────────────────────────────────────────────────────
# GitHub's hosted runners ship the client as `mysql`, Debian/MariaDB as `mariadb`.
SQL_CLIENT="$(command -v mariadb || command -v mysql)"
sql() { "$SQL_CLIENT" --protocol=tcp -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p"$DB_PASSWORD" "$@"; }

if [ "$DB_EXTERNAL" != 1 ]; then
  log "Starting a scratch MariaDB on :$DB_PORT"
  # Snapshot isolation is ON by default from MariaDB 11.6.2 and makes concurrent
  # ledger transfers that share a balance row fail with error 1020 under Treasury's
  # REPEATABLE READ. The JVM suites pin mariadb:10.11 for the same reason; match it.
  MARIADBD="$(command -v mariadbd || echo /usr/sbin/mariadbd)"
  mariadb-install-db --no-defaults --datadir="$WORK/mariadb" --auth-root-authentication-method=normal >/dev/null
  "$MARIADBD" --no-defaults --datadir="$WORK/mariadb" --socket="$WORK/mariadb.sock" \
      --port="$DB_PORT" --bind-address=127.0.0.1 --max-connections=300 \
      --innodb-snapshot-isolation=OFF >"$WORK/mariadb.log" 2>&1 &
  PIDS+=($!)
  wait_for_port "$DB_PORT" 60 MariaDB "${PIDS[-1]}"
  "$SQL_CLIENT" --socket="$WORK/mariadb.sock" -u root -e \
      "ALTER USER 'root'@'localhost' IDENTIFIED BY '$DB_PASSWORD';
       CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED BY '$DB_PASSWORD';
       GRANT ALL ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION;"
fi

log "Migrating $DB_NAME with economy-flyway"
sql -e "DROP DATABASE IF EXISTS \`$DB_NAME\`; CREATE DATABASE \`$DB_NAME\`;"
(cd "$ROOT" && FLYWAY_URL="jdbc:mysql://$DB_HOST:$DB_PORT/$DB_NAME" FLYWAY_USER="$DB_USER" \
    FLYWAY_PASSWORD="$DB_PASSWORD" FLYWAY_SCHEMA="$DB_NAME" \
    sh ./gradlew --console=plain -q :economy-flyway:flywayMigrate)

# ── Server ───────────────────────────────────────────────────────────────────
log "Assembling the Paper server in $SERVER"
cp "$ROOT"/release/*.jar "$SERVER/plugins/"
cp "$CACHE/$LUCKPERMS_JAR" "$CACHE/$GP_JAR" "$CACHE/$WE_JAR" "$CACHE/$WG_JAR" "$SERVER/plugins/"
cp "$TESTS_DIR/diag-plugin/build/libs/DcDiag.jar" "$TESTS_DIR/nexo-stub/build/libs/Nexo.jar" "$SERVER/plugins/"

echo "eula=true" >"$SERVER/eula.txt"
cat >"$SERVER/server.properties" <<EOF
online-mode=false
server-ip=127.0.0.1
server-port=$MC_PORT
enable-rcon=true
rcon.port=$RCON_PORT
rcon.password=$RCON_PASSWORD
broadcast-rcon-to-ops=false
level-type=minecraft\:flat
generate-structures=false
spawn-protection=0
difficulty=peaceful
spawn-monsters=false
spawn-animals=false
spawn-npcs=false
max-players=100
view-distance=4
simulation-distance=4
enforce-secure-profile=false
EOF

# Shipped configuration with database/JWT settings and the external Bytebin
# boundary pointed at this disposable stack; other defaults remain real.
render_config() { # render_config <module dir> <plugin data folder>
  mkdir -p "$SERVER/plugins/$2"
  CFG_HOST="$DB_HOST" CFG_PORT="$DB_PORT" CFG_DATABASE="$DB_NAME" CFG_USERNAME="$DB_USER" \
  CFG_PASSWORD="$DB_PASSWORD" CFG_SECRET="$JWT_SECRET" CFG_BYTEBIN="http://127.0.0.1:$BYTEBIN_PORT/" awk '
    function quoted(v) { gsub(/\\/, "\\\\", v); gsub(/"/, "\\\"", v); return "\"" v "\"" }
    /^[^ \t#]/ { in_db = ($0 ~ /^database:/); in_bytebin = ($0 ~ /^bytebin:/) }
    in_db && /^[ \t]+(host|port|database|username|password):/ {
      indent = $0; sub(/[^ \t].*/, "", indent)
      key = $0; sub(/^[ \t]+/, "", key); sub(/:.*/, "", key)
      if (key == "port") print indent "port: " ENVIRON["CFG_PORT"]
      else print indent key ": " quoted(ENVIRON["CFG_" toupper(key)])
      next
    }
    in_bytebin && /^[ \t]+(post-url|base-url):/ {
      indent = $0; sub(/[^ \t].*/, "", indent)
      key = $0; sub(/^[ \t]+/, "", key); sub(/:.*/, "", key)
      print indent key ": " quoted(ENVIRON["CFG_BYTEBIN"] (key == "post-url" ? "post" : ""))
      next
    }
    /^[ \t]+jwt-secret:/ {
      indent = $0; sub(/[^ \t].*/, "", indent)
      print indent "jwt-secret: " quoted(ENVIRON["CFG_SECRET"])
      next
    }
    { print }
  ' "$ROOT/$1/src/main/resources/config.yml" >"$SERVER/plugins/$2/config.yml"
}
render_config treasury Treasury
render_config business Business
render_config treasury-api-plugin TreasuryAPI
if [ "$PROFILE" = groups ]; then
  sed -i 's/interval-seconds: 1800/interval-seconds: 1/' "$SERVER/plugins/TreasuryAPI/config.yml"
fi

log "Starting the local Bytebin capture service on :$BYTEBIN_PORT"
python3 "$ROOT/ops/integration/bytebin.py" --port "$BYTEBIN_PORT" \
    --evidence "$WORK/bytebin-captures.jsonl" >"$WORK/bytebin.log" 2>&1 &
PIDS+=($!)
wait_for_port "$BYTEBIN_PORT" 15 Bytebin "${PIDS[-1]}"

# These hooks are installed only at startup. A dedicated profile exercises the
# existing gated creation checks without changing the core suite's wilderness fixtures.
if [ "$PROFILE" = protection ]; then
  mkdir -p "$SERVER/plugins/ChestShop"
  printf 'WORLDGUARD_INTEGRATION: true\nGRIEFPREVENTION_INTEGRATION: true\n' \
    >"$SERVER/plugins/ChestShop/config.yml"
fi

# Every mock player connects from 127.0.0.1; GriefPrevention's default of three
# logins per address kicks the fourth. Fresh-spawn PvP protection is off because it
# greets every new player with a chat line ("You're protected from attack…") at an
# unpredictable moment after join, in the middle of the replies tests assert on.
mkdir -p "$SERVER/plugins/GriefPreventionData"
printf 'GriefPrevention:\n  MaxPlayersPerIpAddress: 0\n  PvP:\n    ProtectFreshSpawns: false\n' \
    >"$SERVER/plugins/GriefPreventionData/config.yml"

# LuckPerms tells every op about each permission change ("[LP] LOG > ..."), on its own
# thread and some time after the change applies. Tests op players and change their
# permissions constantly, so those lines would land in the middle of the chat they assert
# on. Every other LuckPerms setting keeps its default.
mkdir -p "$SERVER/plugins/LuckPerms"
printf 'log-notify: false\n' >"$SERVER/plugins/LuckPerms/config.yml"

# The tests' barrier sentinel (/dcsync, support/Barrier.java) is a chat command, and
# vanilla kicks a non-op player for spam after ~10 commands in quick succession. Exempt
# only the sentinel (Spigot fills in every other default); /skill is Spigot's default.
printf 'commands:\n  spam-exclusions:\n  - /skill\n  - /dcsync\n' >"$SERVER/spigot.yml"

log "Starting Paper on :$MC_PORT (RCON :$RCON_PORT)"
(cd "$SERVER" && exec java -Xms1G -Xmx2G -Dcom.mojang.eula.agree=true \
    -jar "$CACHE/$PAPER_JAR" --nogui >"$WORK/server.log" 2>&1) &
PIDS+=($!)
PAPER_PID="${PIDS[-1]}"
wait_for_port "$RCON_PORT" 300 Paper "$PAPER_PID"

# A plugin that failed to enable leaves the server up but the suite meaningless.
for plugin in Treasury Business TreasuryAPI ChestShop LuckPerms DcDiag; do
  if grep -qE "Error occurred while enabling $plugin |Could not load .*$plugin" "$WORK/server.log"; then
    echo "$plugin failed to enable — see $WORK/server.log" >&2
    exit 1
  fi
done

# A local `build` also creates Spring Boot's non-executable *-plain.jar. Never
# let the launch glob select it (or silently choose between stale versions).
REST_JARS=()
for candidate in "$ROOT"/treasury-rest-api/build/libs/treasury-rest-api-*.jar; do
  if [[ -f "$candidate" && "$candidate" != *-plain.jar ]]; then
    REST_JARS+=("$candidate")
  fi
done
if [ "${#REST_JARS[@]}" -ne 1 ]; then
  echo "Expected exactly one executable treasury-rest-api jar, found ${#REST_JARS[@]}" >&2
  exit 1
fi

log "Starting the REST API on :$REST_PORT"
DB_URL="jdbc:mariadb://$DB_HOST:$DB_PORT/$DB_NAME" DB_USERNAME="$DB_USER" DB_PASSWORD="$DB_PASSWORD" \
    java -jar "${REST_JARS[0]}" \
    --server.port="$REST_PORT" --api.jwt-secret="$JWT_SECRET" "${REST_PROFILE_ARGS[@]}" \
    --treasury.webhook.insecure-test-targets="127.0.0.1:$WEBHOOK_PORT" >"$WORK/rest-api.log" 2>&1 &
PIDS+=($!)
wait_for_port "$REST_PORT" 120 "REST API" "${PIDS[-1]}"

# ── Tests ────────────────────────────────────────────────────────────────────
log "Running the integration tests"
# The REST settings go by env var: tests/build.gradle.kts only forwards the
# connection -D properties listed there to the forked test JVM.
(cd "$ROOT" && RESTAPI_BASE="http://127.0.0.1:$REST_PORT/api/v1" RESTAPI_JWT_SECRET="$JWT_SECRET" \
    BYTEBIN_URL="http://127.0.0.1:$BYTEBIN_PORT/" WEBHOOK_RECEIVER_PORT="$WEBHOOK_PORT" IT_SERVER_DIR="$SERVER" IT_EXPLORER_FIXTURE="$WORK/explorer-fixture.json" \
    IT_GROUPS_PROFILE="$GROUPS_PROFILE" IT_WEBHOOK_FAILURE_PROFILE="$WEBHOOK_FAILURE_PROFILE" IT_TAX_PROFILE="$TAX_PROFILE" IT_RESTART_ENABLED="$RESTART_ENABLED" IT_PAPER_PID="$PAPER_PID" IT_PAPER_JAR="$CACHE/$PAPER_JAR" \
    sh ./gradlew -p "$TESTS_DIR" --console=plain "${GRADLE_TASKS[@]}" \
    -PgriefPreventionJar="$CACHE/$GP_JAR" \
    -Dmockclient.host=127.0.0.1 -Dmockclient.port="$MC_PORT" \
    -Ddevserver.host=127.0.0.1 -Ddevserver.port="$MC_PORT" \
    -Ddevserver.db.url="jdbc:mariadb://$DB_HOST:$DB_PORT/$DB_NAME" \
    -Ddevserver.db.user="$DB_USER" -Ddevserver.db.password="$DB_PASSWORD" \
    -Drcon.host=127.0.0.1 -Drcon.port="$RCON_PORT" -Drcon.password="$RCON_PASSWORD" \
    "${PROFILE_ARGS[@]}" \
    "${TEST_ARGS[@]}")

if [ "$PROFILE" = explorer ]; then
  source "$ROOT/ops/integration/explorer.sh"
fi
