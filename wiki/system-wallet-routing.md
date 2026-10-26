# Stable SYSTEM wallet routing

SYSTEM wallets route money for plugins. `system_plugin_key` is their immutable
internal identity; `display_name` is an editable label. A label rename must resolve the original plugin to the same
account ID, owner, balance and ledger, including after cache eviction or restart.
The key is not an additional player-addressable name.

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

## Migration and optional preparation

V40/V41 have been withdrawn. A restored pre-V40 database can migrate directly
through the remaining scripts to V42; no global-name collision cleanup is needed.
See [the staging rebuild requirement](name-namespace.md#staging-rebuild).

V42 must capture original plugin identities before any SYSTEM display-label rename.
Ordinary deployment should run Flyway through V42 before renaming labels. The
optional `ops/system-wallet-routing/prepare-v42.sql` wrapper remains available for
a maintenance workflow that needs to capture keys before normal migration. It
sources the unchanged V42 SQL and verifies wallet identity and balances without
changing Flyway history. Run it from the repository root with all writers stopped
and a verified restore point. Later Flyway execution safely repeats V42.

Preparation includes implicitly committed DDL. Completed statements can be
replayed, preserving non-NULL keys; it does not rename labels or infer original
keys from labels that have already changed. Verify original plugin-to-account
mappings and balances before reopening writers.

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
`SystemWalletPreparationIT` starts on a synthetic V39 database with overlapping
player/SYSTEM labels, exercises preparation prefixes and reruns, preserves original
keys after renames, and migrates through V42 without namespace tables.
The normal test harness also migrates a fresh schema through V42.
These source tests do not establish staging deployment or in-game acceptance.
