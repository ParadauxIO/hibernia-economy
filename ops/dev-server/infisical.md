# Local and staging secrets

Infisical is the source of truth for the local SFTP credentials and staging
server secrets. `.env`, `.env.sc`, and `.sandbox/secrets/*` are no longer read or
generated. `infisical_secrets.py` fetches JSON through the CLI, validates a fixed
key allowlist, and replaces itself with the requested process. Secret values
stay in memory and the child environment; none are put in command arguments or
a dotenv export. Failed retrieval prevents the command from starting.

The host uses the existing `economy` project
(`38b00d6d-5567-4e8f-8bea-e4d54947313e`), environment `dev`, on
`https://secrets.paradaux.io`:

| Scope | Folder | Keys |
| --- | --- | --- |
| DemocracyCraft SFTP | `/hibernia-economy/staging/sftp/democracycraft` | `SFTP_HOST`, `SFTP_PORT`, `SFTP_USER`, `SFTP_PASS` |
| StateCraft SFTP | `/hibernia-economy/staging/sftp/statecraft` | Same SFTP keys |
| Host runtime | `/hibernia-economy/staging/server` | `SANDBOX_DB_PASSWORD`, `VOTIFIER_DEFAULT`, `VOTIFIER_MCSL`, `REALTY_QUERY_SECRET`, `DISCORDSRV_STAGING_TOKEN` |
| Shared with Kubernetes | `/development/economy-dev-server` | `DB_PASSWORD` → `ECONOMY_DEV_DB_PASSWORD`, `JWT_SECRET` → `TREASURY_API_JWT`, `TREASURY_ADMIN_TOKEN` |

The two SFTP folders contain production access credentials despite their use by
staging tooling. Folder selection limits injection; project membership remains
the access-control boundary. No new shared copy of the cluster secrets is made.

## Configure a host

Install the Infisical CLI (tested with 0.43.145), Python 3.11+, and the existing
staging prerequisites. Authenticate the service's Unix user with Infisical. The
current host uses its `paradaux` CLI login profile. The launcher also supports
`INFISICAL_TOKEN` provided by a separately managed machine-authentication setup;
it does not pass that token to application children. Keep authentication outside
this repository. An expired login or unavailable Infisical service blocks new
starts; there is no local credential fallback.

Copy `infisical.example.json` to `$SERVER_DIR/dev-deploy/infisical.json`, replacing
each project ID. Set `profile` to the authenticated CLI profile if needed. This
file contains only routing metadata, never secret values. Set `cli` to the
absolute Infisical executable path when it is outside the service PATH. Set
`STAGING_INFISICAL_CONFIG` to use a different metadata file.

Install `infisical_secrets.py`, `sftp.sh`, and `agent.mjs` into `dev-deploy/`.
Install the four scripts in `host/` into the clone root. Those scripts are now
versioned here so a fresh installation has the same secret handling. Preserve
any additional host-specific agent behavior when updating an existing install.
New installations can use `hibernia-dev-server.service`, followed by
`systemctl daemon-reload`. Existing units that launch `node agent.mjs` also work:
on Node 22.15+ the agent replaces itself with the secret loader before starting,
preserving its PID and systemd signal handling. Control commands do not load secrets.

Before removing legacy files, import their existing values without rotating
credentials, compare Infisical readback in memory, and run the config check
below. Restart the agent in its existing deployment state to load the new
configuration, then verify startup. Remove the two dotenv files and the seven
superseded secret files only after the new path works. A previously generated
`.sandbox/inner.sh` may contain old passwords; the updated startup rewrites it
without interpolated secret values.

## Run tools

```bash
# SFTP commands fetch only the selected source's credentials.
bash ops/dev-server/sftp.sh democracycraft lsf remote:/ --max-depth 1
bash ops/dev-server/sftp.sh statecraft lsf remote:/ --max-depth 1

# The installed clone.sh and start-local.sh invoke the loader automatically.
cd "$HOME/democracycraft-clone"
./clone.sh
./start-local.sh

# Direct config checks and maintenance commands use the staging scope.
python3 dev-deploy/infisical_secrets.py staging -- node sandbox-config.mjs --check
```

The systemd unit loads staging secrets when the agent starts. Its subprocesses
inherit those values. Restart the agent after a secret change; running processes
are not hot-reloaded. Keep deployments paused if they were paused before the
restart. Infisical's Kubernetes operator continues to synchronize the shared
cluster folder independently. `setup-cluster-secret.sh` only seeds an unmanaged
cluster from those existing values; it no longer mints a replacement service token.

Minecraft plugins still require secrets in their generated plugin configuration
files. Infisical replaces the authoritative local dotenv/secret-file store; it
does not remove those runtime plugin files, database storage, or historical
production snapshots. Restrict host access accordingly.

## Verification

```bash
MARIADB_HOME="$HOME/.local/mariadb" PYTHONDONTWRITEBYTECODE=1 \
  python3 -m unittest discover -s ops/dev-server -p 'test_*.py' -v
```

Tests cover literal secret values, scope isolation, rejected retrievals, missing
configuration, anonymous MariaDB options, and a generated startup script with no
embedded database passwords, plus the isolated MariaDB snapshot/restore flow.
