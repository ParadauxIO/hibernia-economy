#!/usr/bin/env bash
# Creates (or refreshes) the `economy-dev-server` Secret in the cluster's
# development namespace, which points the dev treasury-rest-api and
# economy-explorer at the dev server's sandbox database:
#
#   DB_USERNAME / DB_PASSWORD   the economy_dev account start-local.sh creates
#   JWT_SECRET                  the dev server TreasuryAPI plugin's api.jwt-secret,
#                               so tokens players get in game work against the
#                               dev REST API
#   TREASURY_ADMIN_TOKEN        a SERVICE token for the explorer's admin calls,
#                               minted for (and recorded in) the sandbox database
#
# Run on the dev server host, as the user that owns the clone, with the dev
# server running (it needs the sandbox database on 127.0.0.1:33306) and kubectl
# pointed at the cluster:
#
#   bash ops/dev-server/setup-cluster-secret.sh [<owner Minecraft UUID>]
#
# The owner is recorded as the initiator of admin transfers made with the
# token; it defaults to Paradaux. Re-running mints a fresh token for the same
# key (valid 180 days) and replaces the Secret; restart the two Deployments to
# pick it up. Nothing secret is passed on a command line.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
SERVER_DIR="${SERVER_DIR:-$HOME/democracycraft-clone}"
SECRETS="$SERVER_DIR/.sandbox/secrets"
OWNER="${1:-22d45362-ccfc-4845-a843-3012aa8dbfd9}"
OWNER_HEX="${OWNER//-/}"
NAMESPACE=development
NAME=economy-dev-server

for f in db-sandbox db-economy-dev treasury-api-jwt; do
  [[ -s $SECRETS/$f ]] || { echo "Missing $SECRETS/$f; start the dev server once first." >&2; exit 1; }
done

export MYSQL_PWD="$(cat "$SECRETS/db-sandbox")"
sql() { mariadb --no-defaults --skip-ssl -h127.0.0.1 -P33306 -usandbox -N s80037_economy "$@"; }

# The SERVICE key: reuse the owner's live one, or create it.
key_id="$(sql -e "SELECT key_id FROM api_keys WHERE key_type = 'SERVICE' AND revoked = 0
                  AND owner_uuid_bin = UNHEX('$OWNER_HEX') ORDER BY key_id DESC LIMIT 1")"
if [[ -z $key_id ]]; then
  key_id="$(node "$REPO/economy-explorer/scripts/mint-service-token.mjs" --create --owner "$OWNER" 2>/dev/null | sql | tail -1)"
  echo "Created SERVICE key $key_id for $OWNER"
fi

# Mint the token; the script prints the matching UPDATE on stderr, which must be
# applied or the API rejects the token.
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
JWT_SECRET="$(cat "$SECRETS/treasury-api-jwt")" \
  node "$REPO/economy-explorer/scripts/mint-service-token.mjs" --key-id "$key_id" --owner "$OWNER" \
  > "$work/token" 2> "$work/apply.sql"
sql < "$work/apply.sql"
echo "Minted a token for SERVICE key $key_id"

# The Secret, built as JSON on stdin so no value appears in argv.
SECRETS="$SECRETS" TOKEN_FILE="$work/token" NAME="$NAME" NAMESPACE="$NAMESPACE" node -e '
  const fs = require("fs");
  const read = (f) => fs.readFileSync(f, "utf8").trim();
  const s = process.env.SECRETS;
  console.log(JSON.stringify({
    apiVersion: "v1",
    kind: "Secret",
    metadata: {
      name: process.env.NAME,
      namespace: process.env.NAMESPACE,
      labels: { "app.kubernetes.io/part-of": "hibernia-economy-dev-server" },
    },
    type: "Opaque",
    stringData: {
      DB_USERNAME: "economy_dev",
      DB_PASSWORD: read(`${s}/db-economy-dev`),
      JWT_SECRET: read(`${s}/treasury-api-jwt`),
      TREASURY_ADMIN_TOKEN: read(process.env.TOKEN_FILE),
    },
  }));
' | kubectl apply -f -

echo "Done. To pick up a changed Secret:"
echo "  kubectl -n $NAMESPACE rollout restart deployment/treasury-rest-api deployment/economy-explorer"
