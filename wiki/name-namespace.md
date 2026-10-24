# Shared name namespace (PAR-501)

Account display names, business names, player directory names and shop aliases
share one persistent reservation namespace after V40. MariaDB arbitrates every
source insert/update, including plugin, REST, API and administrative SQL writers.
IDs, ownership, account types and balances are never changed to resolve a conflict.

## Comparison and projections

Comparison uses MariaDB `utf8mb4_unicode_ci` with surrounding ASCII spaces trimmed.
It is case/accent insensitive, follows that collation's Unicode equivalences, and
keeps punctuation significant (`AB`, `A-B` and `.AB` are different names). There is
no extra Java normalization and no reuse of blocked-term separator filtering.
Display spelling is preserved; new source writes trim surrounding ASCII spaces.
The existing per-kind character/length validation and storage limits still apply:
this migration does not expand Minecraft names or relax command validation.
Null account display names have no claim; nonnull blank names are rejected.
UUID-hex player placeholders are internal directory state, not addressable names.

A player and their own PERSONAL account share identity `P:<UUID>`. A BUSINESS,
GOVERNMENT or SYSTEM account uses `A:<account ID>`; a business uses `F:<firm ID>`.
Sharing a proprietor/owner does not exempt unrelated entities. A business's default
account retains its separate `<business> Corporate Account` name. Shop aliases
project their account identity, so an alias can equal its own account's display
name, but cannot take another account, firm or player name.

`global_name_reservations` has a unique canonical-name key and a reference count.
`global_name_claims` records source projections. Triggers call stored routines that
acquire the new reservation before releasing the previous one. A conflicting
writer fails atomically, including the source statement and tentative count
increment. Subject rows are acquired before locking reads, avoiding missing-row gap locks
that would block Treasury while Business holds an outer creation transaction.
Locks and the unique key work across processes; JVM locks alone are
not sufficient. InnoDB can abort a deadlock loser; callers must retry their whole
transaction rather than partially retrying its statements.

## Lifecycle and external names

Archiving an account or disbanding a firm retains its reservation. The existing
explicit release of an archived firm's name releases that firm projection only;
restoring the firm must reacquire the name and can fail. Accounts have no automatic
archive-release operation. An explicit successful rename releases its previous
current-name projection once no other projection holds it. Historical shop aliases
remain reserved to the account even after clearing/changing/removing its active
alias. Physical source-row deletion retains a reservation tombstone; it is not a
supported way to release financial identities or old sign names.

Minecraft Java/Bedrock name changes never evict another player's registration.
A conflicting login-name update fails and preserves both directory rows and login
epochs. The player receives an explanation, and the failure is logged for an
administrator. The server does not disconnect the player; economy name registration
and login-triggered balance tax are deferred until resolution. UUID-based wallets
remain unchanged. Administrators must verify actual identity and decide which
current-name record to rename or release; no name conflict reassigns a wallet.

Commands render safe conflict feedback. REST reports HTTP 409 `NAME_RESERVED` for
recognized namespace signals without exposing SQL, owner UUIDs or another wallet's
private details. Existing typed/ID resolution continues to identify the same entity;
autocomplete draws from the same constrained source tables.

## Migration and rollout

The namespace script was originally merged as V38 alongside two other V38
scripts. The repair preserves taxation at V38, employment history at V39, and
this namespace at V40, without changing any SQL. The migration guard permits
only the exact renames from that broken tree. Its upgrade rehearsal omits the
two pending collisions from the scratch base archive, migrates through taxation
V38, and then applies V39/V40 and validates normally. A database that applied
employment history or the namespace as V38 from an individual feature branch
needs an explicit history reconciliation before using this sequence; no automatic
Flyway repair is performed.

Do not deploy V40 to an unreviewed existing dataset. Stop **all** writers for the
migration/backfill/trigger installation window; application locks do not cover the
REST process. The migration owner needs the existing schema privileges plus
`CREATE ROUTINE` and `TRIGGER`; application calls use the trigger/routine definer.
Do not grant application code permission to edit reservations as a release API.

1. Take a consistent full backup and verify restoring it into an isolated database.
   Run the candidate [inventory](../ops/name-collision-inventory.sql) there after
   migration through V35. Older schemas require their actual player-table layout;
   do not run queries assuming tables that are not deployed.
   Then run the read-only [V40 blocker report](../ops/name-namespace-preflight.sql)
   on that same consistent snapshot. Zero rows means V40's name guard passes;
   it does not validate Flyway history or certify the release. Unlike the broad
   candidate inventory, this report excludes allowed same-identity projections,
   released firm claims and UUID player placeholders, and flags blank names and
   surrounding whitespace using V40's rules. It retains historical alias claims.
   Keep its names and identifiers in private operator evidence.
2. Review conflicts using immutable IDs. Recognize only the player/own PERSONAL
   and alias/own-account projections above. Review archived and alias-history
   claims too. The inventory deliberately keeps projections visible for review.
3. Prepare an approved ID-specific remediation list: old/new names, verified
   identity, reason, references, and rollback spelling. Verify account/firm links,
   ownership, balances and ledger totals before/after. Never merge wallets, choose
   a lowest-ID winner, or move balances to make a uniqueness migration pass.
   Source data is not silently normalized: existing surrounding whitespace also
   blocks migration until its cleanup is approved.
4. Rehearse V40 against the restored/remediated dataset. Its preflight rejects
   unresolved collisions and blank names before creating namespace tables.
   MariaDB DDL is not transactional: a failed preflight can leave only its helper
   routine and failed Flyway history. Inspect the error, approve corrections and
   repair Flyway history deliberately before retrying; do not disable validation.
5. During the approved writer-stop window, repeat backup/audit, apply migration,
   verify the unique key, source triggers, claim counts and representative
   concurrent conflicts, then start compatible writers and observe errors.

For rollback, keep writers stopped and restore the coordinated database/application
backup when needed. Do not drop reservation tables/triggers while writers run or
blindly roll back after new financial writes. Reconcile intervening writes first.
Staging/production operations and data remediation are separate authorized actions;
creating this PR does not execute them.

## Verification

`GlobalNameNamespaceIT` exercises the real Flyway schema with independent JDBC
connections: directed cross-kind and same-kind conflicts, rename preservation,
case/accent/space variants, own projections, archive/release/restore, aliases,
player-upsert identity safety, transaction rollback and create/create + create/rename
races. Its preflight test executes the migration's actual guard against session-local
pre-migration fixture tables, preserving original names when a collision is rejected.
A nested-writer regression verifies Business can hold its firm transaction open
while Treasury independently creates the default account.
`PlayerDirectoryServiceIT` verifies login epochs and no-eviction behavior through
the production service. Shared/HTTP error tests cover safe conflict reporting.

V41 permits a personal account's sole name claim to follow an administrative owner
change, under the reservation row lock. A name shared with the original player's
current-name projection still cannot move. Explicitly rename that account first
if the ownership transfer is intended; the player's original name remains reserved.
