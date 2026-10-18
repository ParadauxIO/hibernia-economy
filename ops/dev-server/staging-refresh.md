# Refresh the DemocracyCraft staging databases

`refresh-staging.py` exports and restores exactly `s80037_economy`,
`s80037_chestshops`, `s80037_realty`, and `s80037_luckperms`. It never writes
to production. Snapshots taken before LuckPerms was added (three databases)
fail validation; take a new export rather than editing their manifest.
Production credentials are read from plugin configuration files fetched through
the same SFTP connection as `clone.sh`. Prefer dedicated backup credentials for
recurring use; the existing plugin accounts may lack routine-export permissions.

Requires Python 3.11+, `python3-yaml`, MariaDB client tools, rclone, Node,
kubectl, and the JDK needed by the deployed economy checkout. The restore host
must have the existing clone and deployment agent. Run from this repository.

## Export

Choose a private directory **outside the repository**, with room for the source
SQL, a full staging rollback dump, and MariaDB's restored data. Dumps contain
private production data and credentials until sanitization; restrict access and
retain only as long as needed. The tools create private files and do not put
passwords in command arguments.

```bash
export STAGING_WORK="$HOME/staging-refresh/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -m 700 -p "$STAGING_WORK"
bash ops/dev-server/fetch-staging-configs.sh "$STAGING_WORK/source-configs"
python3 ops/dev-server/refresh-staging.py snapshot \
  --source-config-root "$STAGING_WORK/source-configs" \
  --output "$STAGING_WORK/production"
```

TLS is not silently disabled. If the provider has no TLS, use a protected
connection or obtain an approved exception before passing `--source-no-tls`.
The SFTP transport encrypts the configuration download; it does not tunnel SQL
connections. Remove `source-configs` after a successful export.

Each database is dumped in its own InnoDB transaction. These are **not one
cross-database snapshot**, including when two databases share a host. Do not run
schema changes during export. For a precise gameplay snapshot, coordinate a
production write pause with world/plugin-file backups. Non-InnoDB tables and
scheduled database events are refused rather than silently copied inconsistently
or enabled in staging. Handle these with a reviewed backup policy first.

Only snapshots produced by this tool are supported. A completed snapshot has a
`manifest.json` recording timestamps, server versions, schema defaults, object
inventory and SQL checksums. A failed export has no completed manifest; retry
into a new directory. The importer is for trusted database dumps, not arbitrary
SQL uploaded by users. Provider dumps obtained over SFTP need a separate
inspection/adaptation before use; do not manufacture a manifest to bypass this.

## Quiesce staging

Record the existing replica counts and explicit kube context. Suspend any GitOps
reconciliation or autoscaling that would restart these writers during refresh.
Pause other direct DB clients too.

```bash
export STAGING_KUBE_CONTEXT='your-cluster-context'
kubectl --context "$STAGING_KUBE_CONTEXT" -n development get deployment treasury-rest-api economy-explorer
node "$HOME/democracycraft-clone/dev-deploy/agent.mjs" ctl pause
kubectl --context "$STAGING_KUBE_CONTEXT" -n development scale deployment treasury-rest-api economy-explorer --replicas=0
```

Wait for the application pods to terminate. In a separate terminal start the
database alone, with the cluster listener disabled, and keep stdin open:

```bash
cd "$HOME/democracycraft-clone"
DB_ONLY=1 DB_PORT=33306 CLUSTER_DB_BIND=none ./start-local.sh
```

Do not resume the agent while this process owns the database. The restore checks
that the agent is paused and idle, both deployments have zero replicas, no other
SQL clients are connected, the event scheduler is off, and the target datadir is
the clone's `.sandbox/mariadb`. The target address is fixed to localhost:33306.
It takes an exclusive refresh lock and makes a complete rollback snapshot before
dropping any database.

## Restore and verify

```bash
export JAVA_HOME="$HOME/.jdks/jdk-21.0.12.1+1"
python3 ops/dev-server/refresh-staging.py restore \
  --snapshot "$STAGING_WORK/production" \
  --backup-dir "$STAGING_WORK/before-refresh" \
  --kube-context "$STAGING_KUBE_CONTEXT"
```

The tool preserves schema defaults, recreates tables/views/triggers/routines,
maps their definers to the local importing user and compares the object inventory.
It removes webhook subscriptions and deliveries, clears account-link identities
and codes, deletes copied API keys and stored tokens, expires pending business
transfer requests, and advances the webhook cursor. It preserves the financial
history and permissions needed for realistic testing. This is **not general
anonymization**: player identities, transaction descriptions, audit records and
other private data remain. Keep staging restricted. LuckPerms is copied as-is:
it holds player names, permissions and its action log but no credentials, so
staff ranks and permission groups match production.

Sanitization runs before and after migration. The existing staging SERVICE token
is read from `development/economy-dev-server`, validated against the local signing
secret, and its database identity is recreated after copied API keys are cleared.
This preserves environment-specific authentication across refreshes, including
when Infisical manages the Secret. An invalid or expired token fails preflight;
rotate it in its authoritative secret store before retrying. The refresh does
not change signing secrets, service-token values, or secret-store permissions.

Flyway uses the agent's clean
`dev-deploy/repo` checkout and requires its commit to match the deployed plugin
commit. This avoids accidentally migrating to whatever branch is open locally.
There is no automatic baseline, repair or clean. A production schema ahead of the
deployed staging code must be resolved explicitly. The final checks run
`ops/money-invariants.sql` and the config scrubber's check mode. A successful
restore writes `restore-result.json` alongside the rollback snapshot.

Before migration, the V15 upgrade preflight checks for duplicate player names,
duplicate account memberships, active authorizers without active memberships,
and duplicate system-account names. A failure leaves writers paused for review;
the tool does not silently discard conflicting financial or access records.

On success **and failure**, all writers stay paused. After success:

1. Confirm the output reports that the existing staging service identity was
   restored. Do not run `setup-cluster-secret.sh` to rotate a controller-managed
   Secret: Infisical will restore its authoritative value. That script is for
   initial unmanaged setup; refreshes preserve the existing identity instead.
2. Close stdin in the DB-only terminal and wait for MariaDB to stop.
3. Resume the agent with `ctl resume`; it starts the installed plugins and resumes
   normal `develop` deployment polling. This workflow does not pin releases.
4. Restore the recorded API/Explorer replica counts and GitOps/autoscaler state.
   Verify their images are compatible with the migrated schema.
5. Check plugin startup and exercise a real staging transfer, business lookup,
   property lookup and shop purchase, and check `lp listgroups` shows the
   production groups; verify no production webhook destinations
   remain. World files must correspond closely enough to the DB snapshot for
   physical shop/property checks to be meaningful.

## Rollback

Keep writers paused and MariaDB in DB-only mode. Restore the saved snapshot:

```bash
python3 ops/dev-server/refresh-staging.py restore --rollback \
  --snapshot "$STAGING_WORK/before-refresh" \
  --backup-dir "$STAGING_WORK/before-rollback" \
  --kube-context "$STAGING_KUBE_CONTEXT"
```

Rollback requires the plugin commit recorded in the snapshot, restores the exact
saved database contents without sanitizing/migrating, and still checks ledger
invariants. It saves the current database state first. This restores databases
only: if you also changed worlds, plugins or cluster credentials, restore their
matching versions. Re-mint the staging service token if the cluster Secret was
changed after the original backup, updating its authoritative secret store too.
Never resume services after a partial restore.

## Tests

```bash
MARIADB_HOME="$HOME/.local/mariadb" PYTHONDONTWRITEBYTECODE=1 \
  python3 -m unittest discover -s ops/dev-server -p 'test_*.py' -v
```

Tests start a disposable MariaDB with networking disabled and exercise real dump/
restore, views, routines, triggers, secret sanitization, preserved staging service
identity, invalid/expired token rejection and snapshot checksum rejection.
They do not connect to production or change the running staging databases.
