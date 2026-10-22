#!/usr/bin/env bash
# Sourced by run.sh after its real-player Explorer fixture has committed.
# Uses the existing Paper database; never invokes Playwright's managed DB reset.
EXPLORER_PORT="${IT_EXPLORER_PORT:-3100}"
EXPLORER_URL="http://127.0.0.1:$EXPLORER_PORT"
EXPLORER_SECRET="live-disposable-auth-secret-not-for-production"
if [ ! -d "$ROOT/economy-explorer/node_modules" ]; then
  (cd "$ROOT/economy-explorer" && npm ci --ignore-scripts)
fi
if [ "${IT_SKIP_EXPLORER_BUILD:-0}" != 1 ]; then
  log "Building Explorer"
  (cd "$ROOT/economy-explorer" && NEXT_TELEMETRY_DISABLED=1 npm run build >"$WORK/explorer-build.log" 2>&1)
fi
log "Starting Explorer against the same ledger on :$EXPLORER_PORT"
(cd "$ROOT/economy-explorer" && exec env \
    DB_HOST="$DB_HOST" DB_PORT="$DB_PORT" DB_NAME="$DB_NAME" DB_USERNAME="$DB_USER" DB_PASSWORD="$DB_PASSWORD" \
    AUTH_SECRET="$EXPLORER_SECRET" AUTH_URL="$EXPLORER_URL" AUTH_TRUST_HOST=true \
    AUTH_KEYCLOAK_ISSUER=https://identity.example.test/realms/test AUTH_KEYCLOAK_ID=explorer \
    SITE_URL="$EXPLORER_URL" NEXT_TELEMETRY_DISABLED=1 \
    node node_modules/next/dist/bin/next start --hostname 127.0.0.1 --port "$EXPLORER_PORT" \
    >"$WORK/explorer.log" 2>&1) &
PIDS+=($!)
wait_for_port "$EXPLORER_PORT" 120 Explorer "${PIDS[-1]}"
log "Running same-stack browser assertions"
(cd "$ROOT/economy-explorer" && \
    E2E_BASE_URL="$EXPLORER_URL" E2E_AUTH_SECRET="$EXPLORER_SECRET" \
    IT_EXPLORER_FIXTURE="$WORK/explorer-fixture.json" \
    ./node_modules/.bin/playwright test --config playwright.live-server.config.ts)
