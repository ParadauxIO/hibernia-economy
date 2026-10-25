#!/usr/bin/env bash
# =============================================================================
# upgrade-test.sh — prove this checkout's migrations apply on top of a database
# that was migrated by an earlier revision, the way production is upgraded.
#
#   economy-flyway/scripts/upgrade-test.sh <base-ref>
#
#   1. validate the migration guard, drop + recreate $DB_NAME and migrate it
#      with <base-ref>'s migrations (extracted with `git archive`, passed via
#      -PflywayLocations); the pinned pre-cutover release base stops at V15,
#      matching the deployed schema documented in wiki/release-runbook.md;
#   2. migrate it again with this checkout's migrations;
#   3. flywayValidate — applied history vs the files on disk;
#   4. run ops/money-invariants.sql and require status OK, so a migration that
#      breaks the ledger invariants (or the queries behind them) fails here.
#
# Runs check-migrations.mjs first: it rejects edits to already-merged scripts,
# which this test would otherwise report as a checksum mismatch in step 2.
#
# Connection: DB_HOST (127.0.0.1), DB_PORT (3306), DB_USER (root), DB_PASSWORD,
# DB_NAME (economy_upgrade — it is DROPPED). Needs Node 22, JDK 25 and a mariadb/mysql
# client on PATH. CI runs this in the `migrations` job of .github/workflows/ci.yml.
# =============================================================================
set -euo pipefail

BASE_REF="${1:?usage: upgrade-test.sh <base-ref>}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MIGRATIONS="economy-flyway/src/main/resources/db/migration"

# Check before touching the database. The guard owns the exact historical
# exception and fails closed if any migration differs from the permitted blobs.
BASE_TARGET="$(node "$ROOT/economy-flyway/scripts/check-migrations.mjs" "$BASE_REF" --upgrade-target)"
BASE_EXCLUDES="$(node "$ROOT/economy-flyway/scripts/check-migrations.mjs" "$BASE_REF" --upgrade-excludes)"
BASE_TARGET_ARGS=()
if [ -n "$BASE_TARGET" ]; then
  echo "==> Pre-cutover release baseline: migrate base through V$BASE_TARGET"
  BASE_TARGET_ARGS=("-Dflyway.target=$BASE_TARGET")
fi

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-root}"
DB_PASSWORD="${DB_PASSWORD:?set DB_PASSWORD}"
DB_NAME="${DB_NAME:-economy_upgrade}"

SQL_CLIENT="$(command -v mariadb || command -v mysql)"
sql() { "$SQL_CLIENT" --protocol=tcp -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p"$DB_PASSWORD" "$@"; }

export FLYWAY_URL="jdbc:mysql://$DB_HOST:$DB_PORT/$DB_NAME" FLYWAY_USER="$DB_USER" \
       FLYWAY_PASSWORD="$DB_PASSWORD" FLYWAY_SCHEMA="$DB_NAME"
flyway() { (cd "$ROOT" && sh ./gradlew --console=plain ":economy-flyway:$1" "${@:2}"); }

BASE_DIR="$(mktemp -d)"
trap 'rm -rf "$BASE_DIR"' EXIT

echo "==> Recreating $DB_NAME"
sql -e "DROP DATABASE IF EXISTS \`$DB_NAME\`; CREATE DATABASE \`$DB_NAME\`;"

if git -C "$ROOT" cat-file -e "$BASE_REF:$MIGRATIONS" 2>/dev/null; then
  git -C "$ROOT" archive "$BASE_REF" "$MIGRATIONS" | tar -x -C "$BASE_DIR"
  # The guard emits filenames for pinned historical corrections, including
  # staging-only V40/V41 withdrawal. This scratch archive models a restored
  # pre-namespace database, not an in-place rollback of an applied V40 schema.
  # The real database and its Flyway history are never repaired.
  while IFS= read -r migration; do
    if [ -n "$migration" ]; then rm -- "$BASE_DIR/$MIGRATIONS/$migration"; fi
  done <<<"$BASE_EXCLUDES"
  echo "==> Migrating with $BASE_REF's migrations ($(find "$BASE_DIR/$MIGRATIONS" -name "*.sql" | wc -l) files)"
  flyway flywayMigrate -PflywayLocations="filesystem:$BASE_DIR/$MIGRATIONS" "${BASE_TARGET_ARGS[@]}"
else
  echo "==> $BASE_REF has no $MIGRATIONS; upgrading from an empty database"
fi

echo "==> Migrating with this checkout's migrations"
flyway flywayMigrate
echo "==> flywayValidate"
flyway flywayValidate
flyway flywayInfo

echo "==> ops/money-invariants.sql"
result="$(sql --batch "$DB_NAME" <"$ROOT/ops/money-invariants.sql")"
echo "$result"
status="$(printf '%s\n' "$result" | awk -F'\t' 'NR == 2 { print $1 }')"
if [ "$status" != OK ]; then
  echo "money invariants: status '$status' (expected OK)" >&2
  exit 1
fi
echo "Upgrade from $BASE_REF OK."
