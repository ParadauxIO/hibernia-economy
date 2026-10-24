#!/usr/bin/env bash
# Raises the kernel's inotify limits on the dev server host, which fixes
# "Failed to allocate directory watch: Too many open files" from systemctl
# (and file watchers in editors, Node, Gradle and journald).
#
# The default of 128 inotify instances per user is easy to exhaust on a host
# running many services as root. Run as root:
#
#   sudo bash ops/dev-server/raise-inotify-limits.sh
#
# Writes /etc/sysctl.d/60-inotify.conf so the limits survive reboots, applies
# them now, and shows which processes hold the most instances.
set -euo pipefail

[[ $EUID -eq 0 ]] || { echo "Run as root (sudo bash $0)" >&2; exit 1; }

INSTANCES="${INSTANCES:-1024}"
WATCHES="${WATCHES:-1048576}"
CONF=/etc/sysctl.d/60-inotify.conf

echo "Before: max_user_instances=$(sysctl -n fs.inotify.max_user_instances)" \
  "max_user_watches=$(sysctl -n fs.inotify.max_user_watches)"

cat > "$CONF" <<EOF
# Written by hibernia-economy ops/dev-server/raise-inotify-limits.sh
fs.inotify.max_user_instances = $INSTANCES
fs.inotify.max_user_watches = $WATCHES
EOF
sysctl -q -p "$CONF"

echo "After:  max_user_instances=$(sysctl -n fs.inotify.max_user_instances)" \
  "max_user_watches=$(sysctl -n fs.inotify.max_user_watches)"

# Who holds the instances, so a leaking process can be spotted rather than
# just given more room.
echo
echo "inotify instances by user:"
for fd in /proc/[0-9]*/fd/*; do
  [[ $(readlink "$fd" 2>/dev/null) == anon_inode:inotify ]] && stat -c %U "${fd%/fd/*}"
done 2>/dev/null | sort | uniq -c | sort -rn
echo
echo "Top processes:"
for p in /proc/[0-9]*; do
  n=$(find "$p/fd" -lname 'anon_inode:inotify' 2>/dev/null | wc -l)
  (( n > 0 )) && printf '%4d  %-8s %s\n' "$n" "$(stat -c %U "$p")" "$(tr '\0' ' ' < "$p/cmdline" | cut -c1-80)"
done 2>/dev/null | sort -rn | head -10
