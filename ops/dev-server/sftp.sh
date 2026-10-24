#!/usr/bin/env bash
# Run rclone with one source's Infisical SFTP credentials and no config file.
set -euo pipefail
SOURCE="${1:?Usage: sftp.sh democracycraft|statecraft <rclone arguments...>}"
case "$SOURCE" in democracycraft|statecraft) ;; *) echo 'Unknown SFTP source' >&2; exit 2 ;; esac
if [[ ${STAGING_SECRET_SCOPE:-} != "$SOURCE" ]]; then
  exec python3 "$(dirname "$(readlink -f "$0")")/infisical_secrets.py" "$SOURCE" -- bash "$0" "$@"
fi
shift
(( $# )) || { echo 'Provide an rclone command' >&2; exit 2; }
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
exec "$RCLONE" --config /dev/null "$@"
