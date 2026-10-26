# Global name namespace deferred (PAR-501)

V40 (global name reservations) and its dependent V41 (sole-claim owner changes)
have been withdrawn from the Flyway migration set. They were deployed only to
staging. Versions 40 and 41 remain retired; V42 keeps its existing version and
checksum. Copies of the withdrawn SQL exist only as migration-guard test fixtures,
outside Flyway's resource locations.

Names no longer share a global reservation namespace. Existing per-table
constraints still apply: player names, reserved firm names, shop aliases and SYSTEM
routing keys retain their own uniqueness rules. Ambiguous player/government
name lookups return `AMBIGUOUS_NAME`; explicit UUID lookups remain available.
V42 still preserves SYSTEM routing identity across display-label changes.

## Staging rebuild

Before using this revision on staging, stop its writers and rebuild from a backup
taken before V40 was applied, then run the remaining migrations through V42 and
install the matching application bundle. Confirm the restored backup contains no
V40/V41 history or global-name triggers, routines or reservation tables.
Verify original SYSTEM routing keys, wallet IDs, balances and money invariants
before reopening staging. A backup made after V40 does not remove these objects.

Do not delete Flyway history rows or run `repair` to make the existing V40 schema
appear compatible: neither operation removes its enforcement. No production
database needs rollback because V40 was never deployed there.

The migration guard permits only this exact historical withdrawal. Upgrade
rehearsals omit V40/V41 from the disposable base archive to model a restored
database; they do not claim to upgrade a database that already applied them.
