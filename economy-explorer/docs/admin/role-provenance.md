---
title: Role grant provenance
order: 3
description: Inspect user roles, originating groups, recorded grant sources and synchronization freshness.
---

# Role grant provenance

Open **Admin → Roles → Browse user role grants and group provenance**.
Both `roles.read` and `groups.read` are required, including on the server query
boundary. The tool is read-only and uses the existing privileged-read audit log.

Search player name, full UUID, originating Explorer group, or source node/selector.
Combine this with an exact role key, source, and active/inactive filter. Results
are paginated in groups of 50; filters persist when moving between pages.

Each row is one source for one role. Multiple groups, direct assignments, and the
player baseline can grant the same role independently. Active rows combine into
the user's effective roles; the role's current permission definition determines
permissions. An inactive row alone does not establish that the user lacks that
role: another active source can still grant it.

The baseline applies to every signed-in user, including identities without an
economy player record. The table enumerates known player UUIDs; it is not an
inventory of every identity in Keycloak. Unknown player names fall back to UUIDs.

LuckPerms membership is resolved using inherited groups and effective permission
checks in the server's static context. The table shows the configured source node,
not an invented path through upstream LP groups. Neither that inheritance path nor
the LP actor is stored in Explorer. Explorer creation timestamps and actor UUIDs
are displayed where recorded; reconciliation timestamps indicate snapshot freshness,
not the time an upstream permission was originally granted.

Manual grants remain active. LuckPerms snapshots require a configured node and a
lease newer than one hour. Business/Realty snapshots additionally require an exact
match to the current configured selector. The same SQL validity predicate powers
authorization and this table. Historical rows may be deleted by reconciliation;
this view is a current grant inventory, not an immutable grant/revocation history.

The table needs no new migration or sync publisher. TreasuryAPI's existing native
reconciliation task is the owner of synced memberships. Do not introduce a second
publisher or point development at production to populate the view.

## Staging to development verification

The development Explorer and REST API are configured to share the staging sandbox
economy database in the development GitOps overlays. Confirm their **live**
deployment environment and image before treating that routing as current.
TreasuryAPI owns the native startup/periodic LuckPerms job; its configuration is
`reconciliation.enabled: true` and `reconciliation.interval-seconds: 1800`.
The scheduler clamps the interval to at most 30 minutes of server ticks; lag can
increase wall-clock time. Snapshot leases still expire after one wall-clock hour.

A safe existing-job check needs no new grants, player changes or schema migration:

1. Confirm the installed TreasuryAPI version supports the deployed schema and
   that the runtime config enables reconciliation. Source checkout HEAD alone
   does not establish the installed version.
2. Confirm LuckPerms uses the staging LP database and that both development web
   services use the sandbox economy database, with credentials resolved through
   the existing Infisical operations loader.
3. Read aggregate counts of mapped `explorer_group` rows, role bindings, and
   `explorer_group_member` rows grouped by source, including freshness and latest
   sync timestamp. Record manual memberships independently.
4. Observe a later **scheduled** refresh advancing synced timestamps while
   manual grants remain unchanged. Check logs for resolution failures if it does
   not advance before the lease expires. Never print permission rows or player
   identifiers into public evidence.
5. Query the provenance view read-only against the same database. Its role-source
   count can exceed membership count because a group can grant multiple roles.

Coordinate any mapping/configuration write with the staging maintenance owner.
Do not add an external CronJob when the native reconciler already owns these rows.
Do not roll out a held migration or newer HE build to perform this check.
