# Explorer group reconciliation (PAR-101)

TreasuryAPI refreshes Explorer memberships at startup and every
`reconciliation.interval-seconds` (clamped to 1–1800 real seconds). Both jobs use
Paper's asynchronous scheduler independently of server TPS, so low TPS does not
stretch the refresh interval past the one-hour database lease. Jobs are cancelled
when the plugin disables. Failed or slow reconciliation can still let leases expire.
Set
`reconciliation.enabled: false` to stop both crons. Business/Realty bindings run
independently of LuckPerms. Realty is an optional soft dependency; Business is
already required by TreasuryAPI.

Apply Flyway **V37** before deploying the updated plugin and Explorer. Existing
manual and LuckPerms memberships keep their behavior. No group or capability is
automatically created or granted by this migration.

## Configure a source

Create the Explorer group and choose its roles in the existing administrator UI.
An operator then configures a binding in `explorer_group_sync`. The group ID must
already exist. Each group may have one Business binding and one Realty binding,
as well as its existing LuckPerms node and manual members. Configuration is
operator SQL in this release; the Explorer lists derived members and their source
but does not edit these bindings.

```sql
-- Group 12 gets the proprietor and staff with FINANCIAL or ADMIN in firm 7.
INSERT INTO explorer_group_sync (group_id, source, selector)
VALUES (12, 'business', '7')
ON DUPLICATE KEY UPDATE selector = VALUES(selector);

-- Group 13 gets the title holder and current tenant of this precise region.
-- Replace the world UUID and region ID with the server's actual values.
INSERT INTO explorer_group_sync (group_id, source, selector)
VALUES (13, 'realty', '00000000-0000-0000-0000-000000000001/town_plot_1')
ON DUPLICATE KEY UPDATE selector = VALUES(selector);

-- Stop deriving membership from a source; other sources are unaffected.
DELETE FROM explorer_group_sync WHERE group_id = 12 AND source = 'business';
```

Business uses the public `BusinessApi`, including offline staff. Missing or
archived firms yield no members. Firing, demotion, proprietor transfer and firm
archival converge on the next pass. A failed lookup does not publish an incomplete
membership set.

Realty uses the registered `RealtyBackend.getRegionInfo(regionId, worldUuid)`
service through an optional reflection adapter, following Business's existing
Realty integration. Its contract matches the pinned Realty submodule `95eba03`:
freehold `titleHolderId`, leasehold `tenantId`, `endDate` and
`terminationEffectiveDate`. Expired or effectively terminated tenants are excluded;
lease dates use the same server timezone as Realty. Authority and landlord parties
are not personal title holders and do not confer membership. Empty contracts yield
no members. Missing/incompatible Realty fails that binding without affecting
Business or LuckPerms.

## Failure and access behavior

Each source owns only its rows in `explorer_group_member`. Matching memberships
are refreshed, missing ones added, and former ones removed. Manual grants survive
all reconciliation. Every derived grant requires a fresh `synced_at` (less than
one hour old). A crash, disabled cron, unavailable provider or partial database
write therefore cannot extend access indefinitely. Failures are logged with the
group, source and selector and retried on the next pass.

Business/Realty grants also record their exact `sync_selector`. Explorer checks
that it still matches the configured binding on every authorization read. Editing
or removing a binding invalidates its old grants immediately, including when a
previous reconciliation is still running; fresh members appear on the next pass.
Removed bindings' rows are cleaned up by the cron. The source selector comparison
is case-sensitive. As with LuckPerms, changes within a provider have up to one
interval of propagation delay, or up to the remaining lease during an outage.

Publication and removal both check that the resolved binding is still current
(PAR-502). A delayed pass for the previous selector cannot delete memberships
published after retargeting. The current pass can still remove old-selector rows;
this check does not serialize separate publishers resolving the same selector.

`/treasuryapi ui sync` remains the existing LuckPerms self-sync operation. The new
sources use the scheduled reconciliation; they do not alter account relationships,
Treasury balances, or the roles assigned to Explorer groups.

Verification includes resolver unit tests, real-MariaDB mapper tests for
independent sources and stale-write fencing, and Explorer SQL integration tests
for source removal, selector changes, expiry and manual access preservation.
