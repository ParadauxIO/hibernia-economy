# Stable SYSTEM wallet routing

SYSTEM wallets route money for plugins. `system_plugin_key` is their immutable
internal identity; `display_name` is an editable label in the existing global
name namespace. A label rename must resolve the original plugin to the same
account ID, owner, balance and ledger, including after cache eviction or restart.
The key is not an additional player-addressable name or a namespace exemption.

V42 captures each existing SYSTEM wallet's original `display_name` only when its
key is NULL. It preserves non-NULL keys byte-for-byte on replay, even after label
renames. Keys retain V24's `utf8mb4_unicode_ci` comparison and unique-index name.
The existing `_system_plugin` expression is changed to derive from the stable
key, with index replacement in a single ALTER. No applied migration is edited.
Generic mapper inserts explicitly capture SYSTEM keys; a DB insert trigger also
captures missing keys for legacy/direct writers. Non-SYSTEM keys must be NULL.
An update trigger rejects changing a captured key or converting SYSTEM account
types. Display updates do not write the key. Null/blank or colliding keys fail
preflight; this code never chooses an identity or combines wallets.

## Existing database blocked before V40

V42 alone is too late when V40's name preflight rejects existing labels. The
explicit versioned preparation wrapper reuses the canonical V42 SQL **before any
SYSTEM label rename and before retrying V40**. This adds the key and switches its
unique index without recording V42 early in Flyway history. Flyway later runs
V40, V41 and V42 normally. Do not renumber migrations, enable out-of-order mode,
change successful history checksums, or let a failed entry silently bypass V40.

Preparation is additive but contains implicitly committed DDL. A transaction
rollback cannot undo it. Completed statements can be replayed after interruption;
backfill never rederives a captured key. The index is replaced in one DDL rather
than dropped in a separately committed step. All writers must stay stopped across
preparation, approved label remediation, migration and bundle installation.

1. The maintenance owner confirms the intended database, schema/history, all
   writers stopped (server, REST, jobs and external writers), a verified restore
   point and a compatible **complete** plugin bundle. Rehearse the whole sequence
   on a disposable restored database first. Publication/merge and controller
   polling must be synchronized; do not let auto-deploy race this work.
2. Privately record each SYSTEM account ID, original plugin name, owner, balance
   and version. Confirm labels have not already been renamed; if original routing
   identities cannot be proven, stop and obtain an explicit mapping. Do not guess
   a key from a temporary label. No credentials or wallet identities in public logs.
3. From the root of the exact reviewed checkout, using a mode-0600 client defaults
   file with credentials and the intended socket/host, run the explicit procedure:

   ```bash
   mariadb --defaults-extra-file=/private/staging-client.cnf \
     --abort-source-on-error --database=the_verified_staging_database \
     < ops/system-wallet-routing/prepare-v42.sql
   ```

   Never use `--force`. The wrapper snapshots SYSTEM ID/type/owner/label/key and
   balance/version on the same connection, sources V42, and asserts they are
   unchanged (except filling absent keys from original names), with the routing
   unique index present. It does not rename anything or change Flyway history.
   On any failure keep writers stopped, inspect the committed stage and rerun only
   after resolving the cause. Preflight rejects blank/duplicate keys and non-SYSTEM
   key assignments. A new SQL connection can rerun after any completed stage.
4. Verify all captured keys against the private original-ID map and inspect
   `SHOW CREATE TABLE accounts` plus both `system_plugin_key_*` triggers. Only
   then apply separately reviewed collision dispositions to **display labels**.
   This procedure supplies no names and authorizes no collision policy decision.
5. Inspect the known failed V40 entry and partial objects. Where V40 left only
   its preflight helper and a failed history entry, the maintenance owner may
   perform an explicitly reviewed Flyway repair using the unchanged migration
   set, then migrate normally through V42. A different partial state needs its
   own disposition. Validate successful history checksums and the namespace
   claims after migration. Preparation itself never runs `repair` or `migrate`.
6. Install/boot the compatible complete bundle while maintenance remains paused.
   Resolve each original plugin key through both ordinary and locking lookup;
   verify the exact saved account ID, owner, balance, balance-row count and ledger
   invariants. Verify display-name conflicts still reject. Resume only after
   boot, service compatibility and acceptance checks meet the agreed gate.

**Old code is not compatible with renamed SYSTEM labels.** It still routes by
`display_name`. Preparation without label changes permits old-style inserts, but
after a SYSTEM rename never resume the old bundle against that renamed data.
For rollback after renames restore a consistent pre-maintenance database and
matching bundle, or use a separately reviewed reverse migration/remediation plan.
A JAR-only rollback is insufficient. Do not drop the stable key or rederive it
from renamed labels. Production deployment remains a separate operation.

## Verification

`SystemWalletRoutingIT` uses real MyBatis/service wiring and a funded ledger to
verify the original key still resolves the exact wallet after a label rename,
including locking reads, generic SYSTEM creation, direct writers and uniqueness.
`SystemWalletPreparationIT` reproduces V40 failure on a synthetic V39 database,
exercises preparation prefixes and reruns, preserves original keys after renames,
and then repairs only that scratch database's failed history and migrates V40–V42.
The normal test harness also migrates a fresh schema through V42.
These source tests do not establish staging deployment or in-game acceptance.
