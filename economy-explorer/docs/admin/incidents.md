---
title: Incident procedures
order: 8
description: Diagnose missing money, tax and payroll failures, shop recovery, stale Explorer data, and Realty problems.
---

# Incident procedures

Start by recording the affected tenant, version, UTC interval, player UUID,
account/firm IDs and exact operation. Preserve sanitized errors, transaction/trade
IDs and before/after balances. Stop the affected trading/integration if continuing
could compound the problem. Never infer a missing payment from a timeout alone.

## Missing money or a failed transfer

1. Inspect both account histories and the transaction detail. Distinguish gross
   transfer, tax, fine, refund and shop settlement legs. Public totals and cached
   graphs are not proof of one user's balance.
2. Compare the caller's result and server logs. For an API retry, keep the original
   idempotency key/body. For Explorer or in-game commands, establish whether the
   original transfer committed before submitting another.
3. Check archived state, current owner, memberships/authorizers and available funds.
   A permission refusal is not a reason to edit balances directly.
4. If ledger integrity is suspect, pause writers and run the repository's
   [money invariant checks](https://github.com/ParadauxIO/hibernia-economy/blob/develop/ops/money-invariants.sql)
   on an isolated backup or during controlled maintenance. Every reported mismatch
   needs investigation; preserve the snapshot and stop automated repair attempts.
5. If an actual loss is established, use a documented compensating transfer,
   recording the original and correction transaction IDs. Verify both balances
   and notify the incident owner. Do not delete ledger evidence.

## Tax and payroll

| Symptom | Diagnostic sequence | Recovery/verification |
|---|---|---|
| Scheduled tax did not run | `/tax status`; server timezone, cycle enabled flag and plugin participant; uptime at scheduled time; DB errors | Scheduled runs have no catch-up. Agree on any correction after calculating affected owners; manual triggers are new real periods and can double-charge. |
| Personal login tax looks too large | Balance before login, selected flat bracket and elapsed seconds since previous login (including offline time) | Compare the actual ledger charge with `balance × weekly rate × elapsed / 604800`; review config history before compensating. |
| Corporate tax differs across accounts | Firm total, positive account balances, exemption and whole-cent largest-remainder allocation | Compare sum of collected legs with expected tax, including failed legs and period dedup keys. |
| Published rates are stale/unavailable | Last accepted Treasury reload, `economy_config` update times, V32+ schema and write warnings | Repair publication and reload valid config; docs cache rates for up to a minute. Taxation also requires the public REST config endpoint. |
| Salary absent | Online at payout snapshot, highest positive LP salary group, AFK context, enabled flag, funding account and logs | Fix dependency/config; amount/account reloads apply to later payroll, interval changes need restart. Do not manually rerun an entire payroll. |
| Reload rejected | YAML/type/rate validation and log error | Restore last valid file; Treasury retains its previous runtime tax/salary snapshot on rejected reload. Verify live published values after success. |

Salary groups are case-insensitive; inherited groups count and amounts never
stack. AFK skipping depends on the configured LuckPerms context being supplied.
Missing funding accounts or failed LP lookups need diagnosis, not invented salary
backpay. [Treasury](/docs/admin/config-treasury), [Business](/docs/admin/config-business)
and [Realty](/docs/admin/config-realty) have separate tax configuration.

## Business creation and access recovery

For chat incidents, confirm CarbonChat is available and the employee selected the
intended firm channel. Authorized staff can toggle `/business admin chatspy [firm]`
with `business.admin.chatspy`; record the scope and toggle it off after the case.

If creation fails or crashes, inspect the firm ID, roles, default account/link and
owner access before trying to create another firm. Business's startup transaction
covers firm metadata but Treasury account creation is a separate commit boundary;
see the [creation crash-window notes](https://github.com/ParadauxIO/hibernia-economy/blob/develop/wiki/components.md).
Do not delete a suspected orphan account until its ledger and links are understood.

Business repairs incomplete account membership updates at startup and roughly
once per minute. Restore Treasury/DB availability, observe the next recovery pass,
and verify the proprietor, ADMIN staff and FINANCIAL members have their expected
access. `/business account sync <firm> <accountId>` is an operator command that
recomputes access from staff roles; record manual grants before using it. Check
Explorer LP reconciliation separately from in-game Business access.

## ChestShop settlement and goods recovery

1. Record sign location/world UUID, owner/account, item metadata, quantity, direction,
   trade ID and time. Compare actual chest/player inventories with primary settlement,
   sales-tax and any reversal transactions.
2. On failed goods restoration, preserve `plugins/ChestShop/users.db` and logs.
   Its SQLite `trade_recovery` evidence is independent of sale logging:

   ```sql
   SELECT id, trade_id, recorded_at, evidence FROM trade_recovery ORDER BY id;
   ```

3. A row describes a failed attempt, not proven loss. Inspect serialized item
   snapshots and any known settlement/reversal error before compensation. If
   SQLite failed, serialized evidence may exist only in the server log.
4. Reconcile verified goods and money once, recording the outcome against the trade
   ID. There is no automatic retry/compensation command. Retain evidence until the
   case is resolved and archived under your retention policy.
5. Verify a small staging buy and sell with realistic stock, capacity and metadata.
   Restore normal trading only when the original failure is understood.

## ChestShop cutover and registry repair

For a first legacy migration, follow the full
[registry backfill](https://github.com/ParadauxIO/hibernia-economy/blob/develop/wiki/shop-registry-backfill.md)
and [add-on cutover](https://github.com/ParadauxIO/hibernia-economy/blob/develop/wiki/chestshop-add-on-compatibility.md)
procedures, using scripts from the same release revision.

1. Back up both databases, `users.db`/item-code data, configs, Nexo definitions and
   worlds. Pause legacy registry writes; deploy compatible Treasury/ChestShop.
2. Export `Shop` with `ops/export-legacy-shops.sql` and preferences with
   `ops/export-legacy-preferences.sql`. Place the JSONL files in `plugins/ChestShop/`
   as `legacy-shops.jsonl` and `legacy-preferences.jsonl`.
3. Load source worlds with their original UUIDs; run `/chestshop importlegacy` and
   `/chestshop importpreferences` from trusted console/admin. A rejected/stopped
   import is incomplete. Fix the failing row/cause and rerun; existing rows/native
   preferences are preserved, so rerunning does not overwrite differences.
4. Compare source/destination location and UUID sets, items/metadata, owner mappings,
   buy/sell prices, quantity, stock/capacity timestamps, visibility, holograms and
   preferences. Equal row counts alone do not establish a faithful import.
5. Retire the legacy ChestShopDatabase finder after verification. Keep add-ons still
   supplying policy/hooks (including Utilities restrictions). Validate Bolt,
   VaultStorage, BreweryX and WorldGuardExtraFlags behaviors that the server uses,
   including Java/Bedrock navigation and preview persistence.

For subsequent repairs, `/chestshop resync <chunks>` scans **loaded chunks in all
loaded worlds**, from console or an admin player. The argument is the work budget in chunks per tick (clamped to
1–64), not a radius; it does not load missing chunks. WorldEdit/FAWE
cleanup drains in batches and waits for unloaded chunks; resync loaded areas after
an interrupted cleanup. `/find resync` is not a supported repair command.
World renames need explicit old/new-name registry reconciliation: UUID storage
does not automatically remove stale name-keyed rows.

`find.yml` controls finder materials/templates, transit/GPS actions and preview
defaults. Existing native settings win over imported legacy settings. Keep original
item databases and validate old aliases against installed Nexo IDs; do not guess
replacements for missing item definitions. [Configuration details](/docs/admin/config-chestshop).

## Realty property operations

Use [Realty commands](/docs/reference/realty) and the pinned plugin's
[configuration](/docs/admin/config-realty). Check region/world identity, titleholder,
landlord/tenant, authority, tags, lease end and payment deadline before changing a plot.

For a disputed purchase, offer or auction, compare `/realty info`, `/realty history`,
offer inbox/outbox or auction info with the Treasury ledger. Confirm whether the
payment completed, remains partial, or was refunded on expiry before collecting
again. Accepted offers and winning bids have deadlines; expired leases return to
the landlord, while early `/realty unrent` refunds unused time according to the
plugin. Test these paths on staging before changing their settings.

For property-tax disputes, enumerate the owner's regions/tags and apply the first
matching rule to each plot. Counts and exemptions are per rule, not a separate
federal formula. Realty logs collection failures; it does not evict for tax debt.

Back up Realty DB, WorldGuard regions and matching worlds before destructive region
work. `/realty delete ... includeworldguard` can remove the underlying region too.
Changing titleholder/tenant is not proof that a financial refund happened. Verify
both property state and ledger, or restore the matching state during maintenance.

## Explorer or API outage and stale analytics

Check process/proxy and DB independently. `/healthz` only proves Explorer liveness.
Inspect DB connections/slow queries before increasing pool sizes or restarting
replicas. Verify the deployed schema and service version agree. Current aggregates
use the per-transaction `ledger_txn_volume` table introduced in V36; a missing or
failed backfill is a migration problem, not something a browser refresh repairs.

Separate current balances, UTC daily history and last-observed shop stock. Cached
aggregates or an unloaded shop can be stale while the ledger is correct. Compare
source timestamps and check Redis/configuration before deleting caches. Preserve
errors and request/transaction identifiers for escalation; redact credentials,
private webhook URLs and unnecessary personal data.

Close an incident only after the affected journey succeeds, expected refusals
still hold, any financial correction is reconciled and evidence/recovery decisions
are recorded in its task. Database restoration follows
[release and recovery](/docs/admin/release-and-recovery).
