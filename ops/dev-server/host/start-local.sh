#!/usr/bin/env bash
# Start this clone locally for testing, inside a network sandbox.
#
# Two layers keep this copy away from production. First, sandbox-config.mjs
# rewrites the configs before every start: databases point at the local
# MariaDB as user sandbox, and the Discord bot token, webhooks, store, website
# and resource-pack keys are removed (production copies in .sandbox/prod-config/).
# It refuses, and so this script refuses, if anything production-specific is
# left. Second, the server runs in a network sandbox that has only a private
# loopback interface and can write nowhere but this directory; plugins that
# need a remote service will log connection errors.
# Two things are let through, both relayed over unix sockets in .sandbox/:
#   - inbound: players connecting to $PORT on this machine
#   - outbound: the few HTTPS hosts in AUTH_HOSTS (Mojang login, so online-mode
#     and the whitelist work, and two public sites plugins need to start)
#
# The databases are a private MariaDB that runs inside the sandbox (data in
# .sandbox/mariadb), and the production database hostnames resolve to it too,
# in case a config is ever missed. It starts EMPTY, so plugins create fresh
# schemas: no production data.
# Treasury and Business do not create their own tables, so their bundled
# schema files are loaded the first time.
#
# For tooling such as Flyway:
#   DB_PORT=33306 ./start-local.sh     also offers the sandbox database on
#                                      127.0.0.1:33306 of this machine (user
#                                      sandbox, password from Infisical), and on
#                                      this node's Kubernetes pod-network
#                                      address (cilium_host) for the dev
#                                      treasury-rest-api and economy-explorer
#                                      in the development namespace (user
#                                      economy_dev, economy schema only,
#                                      password from Infisical). CLUSTER_DB_BIND=<ip>
#                                      or =none overrides that address.
#   DB_ONLY=1 DB_PORT=33306 ./start-local.sh
#                                      runs just the database until stdin closes
#
# Production runs UniverseSpigot through ULoader.jar, which needs the licence
# key and its licensing server. Staging is pinned to Paper 26.3 build 206
# (PAR-499); SERVER_JAR can explicitly override this runtime.
set -euo pipefail

cd "$(dirname "$(readlink -f "$0")")"
export SERVER_DIR="$(pwd)"
if [[ ${STAGING_SECRET_SCOPE:-} != staging ]]; then
  exec python3 "$SERVER_DIR/dev-deploy/infisical_secrets.py" staging -- bash "$0" "$@"
fi
node "$SERVER_DIR/sandbox-config.mjs"
PORT="${PORT:-25565}"
# Outbound HTTPS hosts let through: Mojang login, plus two public sites that
# HeadDatabase (its head catalogue) and BlueMapPlayerControl (its version
# check) refuse to start without. Nexo uses hermes.nexomc.com to refresh
# its licensed RequiredPack/DefaultPack assets (ADT-193).
AUTH_HOSTS=(sessionserver.mojang.com api.mojang.com api.minecraftservices.com discovery.minecraftservices.com minecraft-heads.com github.com hermes.nexomc.com spark-usercontent.lucko.me)
# ADT-193: only the authorized staging bot API and gateway, with all other isolation intact.
if [[ -n ${DISCORDSRV_STAGING_TOKEN:-} ]]; then
  AUTH_HOSTS+=(discord.com gateway.discord.gg)
fi
# Names that resolve to the sandbox itself: the production database hosts, and
# the production container's hostname, which Plan and Realty bind to.
DB_HOSTS=(ash-251032.bloom.host ash-251006.bloom.host ash-ppg-1051.bloom.host 4d6eabc1-d8ae-425c-b716-a1871de9d943)
DATABASES=(
  s80037_economy s80037_chestshops s80037_realty s80037_playerpoints s80037_vaultstorage
  s80037_luckperms s80037_punishments s93630_punishments s80037_elections_new
  s80037_families s80037_deportation s80037_homeland_security s80037_excellentjobs
  s80037_friends s80037_post s80037_surveyplus s80037_notifications s80037_plan
  s80037_pets s80037_servervariables s80037_bluemap s80037_temp s1795_mycommands
)
MARIADB_HOME="${MARIADB_HOME:-$HOME/.local/mariadb}"
[[ -x $MARIADB_HOME/bin/mariadbd ]] || { echo "No MariaDB server in $MARIADB_HOME" >&2; exit 1; }

JAVA_HOME="${JAVA_HOME:-$(ls -d "$HOME"/.local/jdks/graalvm-community-openjdk-25* 2>/dev/null | tail -1)}"
[[ -x $JAVA_HOME/bin/java ]] || { echo "No Java 25 found; set JAVA_HOME" >&2; exit 1; }
INNER_PORT="$(sed -n 's/^server-port=//p' server.properties)"
: "${SANDBOX_DB_PASSWORD:?Missing Infisical SANDBOX_DB_PASSWORD}"
: "${ECONOMY_DEV_DB_PASSWORD:?Missing Infisical ECONOMY_DEV_DB_PASSWORD}"
# Passwords are used in SQL below. Escape for MariaDB string literals.
SANDBOX_DB_SQL=$(printf %s "$SANDBOX_DB_PASSWORD" | sed "s/\\\\/\\\\\\\\/g; s/'/''/g")
ECONOMY_DEV_DB_SQL=$(printf %s "$ECONOMY_DEV_DB_PASSWORD" | sed "s/\\\\/\\\\\\\\/g; s/'/''/g")

export SANDBOX_DB_SQL ECONOMY_DEV_DB_SQL
SOCKS="$SERVER_DIR/.sandbox"
mkdir -p "$SOCKS"
rm -f "$SOCKS"/*.sock
trap 'kill $(jobs -p) 2>/dev/null || true' EXIT

# Outside half of the relays. Each login host gets its own loopback address
# inside the sandbox, named in the hosts file the sandbox sees.
socat "TCP-LISTEN:$PORT,fork,reuseaddr" "UNIX-CONNECT:$SOCKS/minecraft.sock" &
if [[ -n ${DB_PORT:-} ]]; then
  socat "TCP-LISTEN:$DB_PORT,bind=127.0.0.1,fork,reuseaddr" "UNIX-CONNECT:$SOCKS/database.sock" &
  # For pods only: cilium_host is not routed from outside the cluster, unlike
  # the public interface.
  CLUSTER_DB_BIND="${CLUSTER_DB_BIND:-$(ip -4 -o addr show cilium_host 2>/dev/null | awk '{print $4}' | cut -d/ -f1)}"
  if [[ -n $CLUSTER_DB_BIND && $CLUSTER_DB_BIND != none ]]; then
    socat "TCP-LISTEN:$DB_PORT,bind=$CLUSTER_DB_BIND,fork,reuseaddr" "UNIX-CONNECT:$SOCKS/database.sock" &
  fi
fi
if [[ -n ${DB_ONLY:-} ]]; then
  SERVER_CMD='echo "Sandbox database is up; close stdin to stop it."; cat > /dev/null'
else
  SERVER_CMD="\"$JAVA_HOME/bin/java\" -Xms${HEAP_MIN:-4G} -Xmx${HEAP_MAX:-6G} -jar \"${SERVER_JAR:-paper-26.3-206.jar}\" --nogui \"\$@\""
fi
{
  echo "127.0.0.1 localhost ${DB_HOSTS[*]}"
  n=2
  for host in "${AUTH_HOSTS[@]}"; do
    socat "UNIX-LISTEN:$SOCKS/$host.sock,fork" "TCP:$host:443" &
    echo "127.0.0.$n $host"
    n=$((n + 1))
  done
} > "$SOCKS/hosts"

# Inside half of the relays, the database, then the server itself. The
# sandbox maps this user to root so the relays can listen on port 443; files
# stay owned by you.
cat > "$SOCKS/inner.sh" <<EOF
socat "UNIX-LISTEN:$SOCKS/minecraft.sock,fork" "TCP:127.0.0.1:${INNER_PORT:-25565}" &
socat "UNIX-LISTEN:$SOCKS/database.sock,fork" "TCP:127.0.0.1:3306" &
while read -r addr host _; do
  [[ \$host == localhost ]] && continue
  socat "TCP-LISTEN:443,bind=\$addr,fork,reuseaddr" "UNIX-CONNECT:$SOCKS/\$host.sock" &
done < /etc/hosts

db=(--no-defaults --basedir="$MARIADB_HOME" --datadir="$SOCKS/mariadb" --user=root)
[[ -d $SOCKS/mariadb/mysql ]] || "$MARIADB_HOME/scripts/mariadb-install-db" "\${db[@]}" > "$SOCKS/mariadb-install.log" 2>&1
"$MARIADB_HOME/bin/mariadbd" "\${db[@]}" --bind-address=127.0.0.1 --port=3306 \\
  --socket=/tmp/mariadb.sock --max-connections=1000 --innodb-buffer-pool-size=1G --log-error="$SOCKS/mariadb.log" &
for _ in \$(seq 60); do [[ -S /tmp/mariadb.sock ]] && break; sleep 1; done
sql=("$MARIADB_HOME/bin/mariadb" --no-defaults --socket=/tmp/mariadb.sock)
{
  for name in ${DATABASES[*]}; do echo "CREATE DATABASE IF NOT EXISTS \\\`\$name\\\`;"; done
  # sandbox: every plugin config uses it (sandbox-config.mjs), and so does
  # tooling that connects through DB_PORT. economy_dev: the dev
  # treasury-rest-api and economy-explorer in the cluster, economy schema only.
  for host in % localhost; do
    echo "CREATE OR REPLACE USER 'sandbox'@'\$host' IDENTIFIED BY '\$SANDBOX_DB_SQL';"
    echo "GRANT ALL PRIVILEGES ON *.* TO 'sandbox'@'\$host' WITH GRANT OPTION;"
    echo "CREATE OR REPLACE USER 'economy_dev'@'\$host' IDENTIFIED BY '\$ECONOMY_DEV_DB_SQL';"
    echo "GRANT SELECT, INSERT, UPDATE, DELETE, EXECUTE ON \\\`s80037_economy\\\`.* TO 'economy_dev'@'\$host';"
  done
} | "\${sql[@]}"
# (Only for the 2.2.x jars: newer builds use the economy-flyway migrations,
# which are applied from the repository instead.)
if compgen -G "$SERVER_DIR/plugins/Treasury-2.2*.jar" > /dev/null \\
  && [[ -z \$("\${sql[@]}" -N -e "SHOW TABLES IN s80037_economy LIKE 'accounts'") ]]; then
  # Treasury and Business share this database and reference each other's
  # tables, so both load with foreign key checks off.
  {
    echo "SET FOREIGN_KEY_CHECKS=0;"
    unzip -p "$SERVER_DIR"/plugins/Treasury-*.jar schema.sql
    unzip -p "$SERVER_DIR"/plugins/Business-*.jar sql/schema.sql
  } | "\${sql[@]}" s80037_economy
fi
# ChestShopDatabase does not create its tables either; its schema is not in
# the jar, so a copy from its repository is kept in sandbox-schemas/.
if [[ -z \$("\${sql[@]}" -N -e "SHOW TABLES IN s80037_chestshops LIKE 'Shop'") ]]; then
  "\${sql[@]}" s80037_chestshops < "$SERVER_DIR/sandbox-schemas/chestshop-database.sql"
fi

# DemocracyPost creates its tables only when told to, and fails if told to
# when they already exist, so the flag follows the state of the database.
if [[ -z \$("\${sql[@]}" -N -e "SHOW TABLES IN s80037_post LIKE 'POST_PACKAGE'") ]]; then init=true; else init=false; fi
sed -i "s/^\\( *initialize-schema:\\).*/\\1 \$init/" "$SERVER_DIR/plugins/DemocracyPost/settings.yml"

$SERVER_CMD
status=\$?
"$MARIADB_HOME/bin/mariadb-admin" --no-defaults --socket=/tmp/mariadb.sock shutdown
exit \$status
EOF

bwrap \
  --unshare-user --unshare-net --unshare-pid --unshare-ipc --die-with-parent \
  --uid 0 --gid 0 --cap-add CAP_NET_BIND_SERVICE \
  --ro-bind /usr /usr --ro-bind /bin /bin --ro-bind /sbin /sbin \
  --ro-bind /lib /lib --ro-bind /lib64 /lib64 --ro-bind /etc /etc \
  --dev /dev --proc /proc --tmpfs /tmp --tmpfs /run \
  --ro-bind "$SOCKS/hosts" /etc/hosts \
  --tmpfs "$HOME" \
  --ro-bind "$JAVA_HOME" "$JAVA_HOME" \
  --ro-bind "$MARIADB_HOME" "$MARIADB_HOME" \
  --bind "$SERVER_DIR" "$SERVER_DIR" \
  --chdir "$SERVER_DIR" \
  --setenv HOME "$SERVER_DIR" \
  bash "$SOCKS/inner.sh" "$@"
