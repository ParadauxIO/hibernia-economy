#!/usr/bin/env bash
# Clone the Pterodactyl server's files over SFTP directly into this directory.
# Safe to re-run: files already here with the same size and mtime are skipped.
set -euo pipefail

cd "$(dirname "$(readlink -f "$0")")"
export SERVER_DIR="$PWD"

# SFTP credentials are scoped to this source and fetched at invocation time.
if [[ ${STAGING_SECRET_SCOPE:-} != democracycraft ]]; then
  exec python3 "$PWD/dev-deploy/infisical_secrets.py" democracycraft -- bash "$0" "$@"
fi

: "${SFTP_HOST:?missing from Infisical}" "${SFTP_PORT:?missing from Infisical}" "${SFTP_USER:?missing from Infisical}"
: "${SFTP_PASS:?SFTP_PASS is empty in Infisical}"

RCLONE="$(command -v rclone || echo "$HOME/.local/bin/rclone")"
[[ -x $RCLONE ]] || { echo "rclone not found (https://rclone.org/downloads/)" >&2; exit 1; }

# Remote paths that are not cloned (rclone filter syntax, / anchors at the root).
EXCLUDES=(
  '/plugins/CoreProtect/**'
  '/plugins/Essentials/userdata/**'
  '/logs/**'
  '*.tar.gz'
  # Per-player files: several hundred thousand tiny files, hours over SFTP.
  '/*/playerdata/**'
  '/*/advancements/**'
  '/*/stats/**'
  '/*/DIM*/playerdata/**'
  '/*/DIM*/advancements/**'
  '/*/DIM*/stats/**'
  # Rendered map tiles (regenerable) and per-player plugin data.
  '/plugins/BlueMap/web/**'
  '/plugins/BlueMap/web.old/**'
  '/plugins/BountyHunters/userdata/**'
  '/plugins/CarbonChat/users/**'
  # Not regular files; the server refuses to open them.
  '/pinned_*'
  # Never let a remote file replace these scripts or the credentials.
  '/clone.sh'
  '/start-local.sh'
  '/sandbox-config.mjs'
  '/sandbox-schemas/**'
  '/hibernia-economy-previous/**'
  '/.sandbox/**'
  '/.env*'
  '/dev-deploy/**'
  '/sandbox-discord.mjs'
  # Edited here for local testing (whitelist on, no proxy forwarding); the
  # production copies are kept in .sandbox/prod-config/.
  '/server.properties'
  '/config/paper-global.yml'
  '/whitelist.json'
  # DemocracyPost creates its own tables locally (initialize-schema: true).
  '/plugins/DemocracyPost/settings.yml'
)

# Pterodactyl's SFTP has no shell, so rsync and scp are out; rclone speaks
# plain SFTP, lists each directory once and transfers many files in parallel.
# The remote is defined through the environment, so nothing is written to an
# rclone config file. The host key is checked against ~/.ssh/known_hosts,
# which stores the name in lower case.
export RCLONE_CONFIG_REMOTE_TYPE=sftp
export RCLONE_CONFIG_REMOTE_HOST="${SFTP_HOST,,}"
export RCLONE_CONFIG_REMOTE_PORT="$SFTP_PORT"
export RCLONE_CONFIG_REMOTE_USER="$SFTP_USER"
export RCLONE_CONFIG_REMOTE_SHELL_TYPE=none
export RCLONE_CONFIG_REMOTE_DISABLE_HASHCHECK=true
export RCLONE_CONFIG_REMOTE_KNOWN_HOSTS_FILE="$HOME/.ssh/known_hosts"
RCLONE_CONFIG_REMOTE_PASS="$(printf %s "$SFTP_PASS" | "$RCLONE" obscure -)"
export RCLONE_CONFIG_REMOTE_PASS

# copy, not sync: nothing local is ever deleted.
"$RCLONE" copy remote:/ . \
  --config /dev/null \
  --transfers "${JOBS:-12}" \
  --checkers 8 \
  --stats 60s --stats-one-line --stats-log-level NOTICE \
  "${EXCLUDES[@]/#/--exclude=}"

# The configs just copied are production's; start-local.sh scrubs them again
# (sandbox-config.mjs) before the server next starts.
echo "Clone complete: $(du -sh . | cut -f1) in $(pwd)"
