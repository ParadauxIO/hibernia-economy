#!/usr/bin/env bash
# Fetch only the four production DB configurations, using clone.sh's connection.
# These files contain production credentials. Destination must be new/private.
set -euo pipefail
umask 077
SERVER_DIR="${SERVER_DIR:-$HOME/democracycraft-clone}"
DESTINATION="${1:?Usage: fetch-staging-configs.sh /private/new-directory}"
[[ ! -e "$DESTINATION" ]] || { echo 'Destination must not exist' >&2; exit 1; }
source "$SERVER_DIR/.env"
: "${SFTP_HOST:?}" "${SFTP_PORT:?}" "${SFTP_USER:?}" "${SFTP_PASS:?}"
RCLONE="$(command -v rclone || echo "$HOME/.local/bin/rclone")"
export RCLONE_CONFIG_REMOTE_TYPE=sftp
export RCLONE_CONFIG_REMOTE_HOST="${SFTP_HOST,,}"
export RCLONE_CONFIG_REMOTE_PORT="$SFTP_PORT"
export RCLONE_CONFIG_REMOTE_USER="$SFTP_USER"
export RCLONE_CONFIG_REMOTE_SHELL_TYPE=none
export RCLONE_CONFIG_REMOTE_DISABLE_HASHCHECK=true
export RCLONE_CONFIG_REMOTE_KNOWN_HOSTS_FILE="$HOME/.ssh/known_hosts"
RCLONE_CONFIG_REMOTE_PASS="$(printf %s "$SFTP_PASS" | "$RCLONE" obscure -)"
export RCLONE_CONFIG_REMOTE_PASS
mkdir -m 700 -p "$DESTINATION"
for file in Treasury/config.yml ChestShopDatabase/database-settings.yml Realty/database.yml \
            LuckPerms/config.yml; do
  "$RCLONE" copyto "remote:/plugins/$file" "$DESTINATION/$file" --config /dev/null
done
echo "Fetched database configs into $DESTINATION; remove them after exporting."
