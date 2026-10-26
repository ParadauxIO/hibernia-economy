---
title: Admin-operator runbook
order: 0
description: Start here for routine operations, safe changes, incident response, and recovery across the economy stack.
---

# Admin-operator runbook

Use this as the operating checklist for one economy tenant. Record the server,
database, deployed commit/image tags, operator, time in UTC and task reference
before a change. The website permission `docs.admin.read` grants access to this
section; it does not grant the permissions needed to perform the procedures.

## Find the procedure

| Task | Procedure |
|---|---|
| Install/configure services; diagnose missing dependencies | [Service configuration](/docs/admin/config-services) |
| Check players, accounts, firms, transfers and audit records | [Explorer administration](/docs/admin/explorer-administration) |
| Grant/revoke roles, groups or account access | [Explorer access](/docs/admin/roles), [government accounts](/docs/admin/government-accounts) |
| Explain a user's effective roles or stale group sources | [Role grant provenance](/docs/admin/role-provenance) |
| Compare estimated volume with raw ledger records | [Vault bridge analytics](/docs/admin/bridge-analytics) |
| Recover a login; issue, revoke or rotate API/SERVICE credentials | [Access and credentials](/docs/admin/access-and-credentials) |
| Set issuer limits or investigate API errors | [Access and credentials](/docs/admin/access-and-credentials#api-errors-and-rate-limits) |
| Change tax, salaries, shop rules or property settings | [Configuration](/docs/admin/configuration), [operator commands](/docs/admin/operator-commands) |
| Missing money, missed payroll, failed shops or stale data | [Incident procedures](/docs/admin/incidents) |
| Diagnose or restore webhook delivery | [Webhooks](/docs/admin/webhooks#delivery-incident-procedure) |
| Back up, release, migrate, roll back or refresh staging | [Release and recovery](/docs/admin/release-and-recovery) |
| Import legacy shops/preferences or reconcile world edits | [ChestShop operations](/docs/admin/incidents#chestshop-cutover-and-registry-repair) |
| Operate property sales, leases, offers and auctions | [Realty operations](/docs/admin/realty-operations) |

## Before opening a new or restored tenant

1. Apply the release's Flyway migrations before starting matching application
   versions. Verify the target database explicitly; do not reuse another tenant's
   credentials or service identity. Follow [release and recovery](/docs/admin/release-and-recovery).
2. Configure Treasury, Business, ChestShop and TreasuryAPI for the same economy
   schema. Realty has its own configured database. Preserve world UUIDs, region
   definitions and plugin files when restoring game state.
3. Confirm Treasury starts, publishes its API and creates the configured primitive
   accounts. Verify Business/ChestShop/Realty integrations and TreasuryAPI's
   LuckPerms reconciliation in startup logs.
4. Configure Explorer sign-in, link an operator account and grant its configured
   role through trusted console `/treasuryapi ui user add admin <player>`.
   Verify the actual role permissions and keep a second recovery administrator.
5. Configure the REST API and Explorer SERVICE token. Check read-only pages,
   sign-in/linking, permitted and denied account histories, and live tax rates.
6. On staging, exercise a small transfer, firm lookup/account access, shop buy and
   sell, property lookup, and a webhook to a controlled test endpoint. Compare
   resulting ledger entries and balances. Do not treat a green process probe as
   evidence that these journeys work.

## Daily and after-deploy checks

| Check | Expected result | If it fails |
|---|---|---|
| Plugin startup and error logs | Required plugins enabled; no repeated DB, settlement or reconciliation errors | Identify the first dependency error; [incident triage](/docs/admin/incidents) |
| Explorer `/healthz` | HTTP 200 `ok` | Check process/proxy; this probe **does not query the DB** |
| REST management `/actuator/health` | Healthy on the configured management port (prod default 8081) | Inspect service/DB health through private monitoring |
| Explorer account/market page | Queries complete; observation timestamps make sense | Compare source timestamps, DB health and cache; avoid repeated restarts |
| `/tax status` | Expected enabled cycles, participants and next-fire times | Check timezone/config/plugin registration; do not trigger tax as a health check |
| Treasury published rates | Match last accepted settings and their update time | Inspect publication errors and migration state; [tax incidents](/docs/admin/incidents#tax-and-payroll) |
| API key expiry and webhook health | Credentials have renewal lead time; delivery failures understood | [Credentials](/docs/admin/access-and-credentials), [webhooks](/docs/admin/webhooks) |
| Backups | Latest complete backup and a recent successful isolated restore rehearsal | Repair backup process before schema/destructive maintenance |

Review privileged role/group assignments, API-key issuer limits, archived accounts,
recovery evidence and backup retention regularly. Record who owns each exception
and when it should be removed.

## Change and incident discipline

Inspect before changing: identify exact account/firm IDs, balances, relationships,
transaction IDs and effective settings. Save the previous configuration and record
the intended result. Use the supported UI/API/commands to move money; never edit
`balances` or ledger postings to manufacture a correction.

After a write, reload the relevant page and inspect the ledger or persisted state.
A timeout or error page does not prove a transfer failed. The Explorer transfer
form does not send a stable idempotency key: inspect the ledger before submitting
again. Refund through a separate explained transfer, preserving the original entry.

If behavior is unexplained, stop the affected activity, retain evidence and follow
[incident procedures](/docs/admin/incidents). A configuration rollback only changes
future behavior; database/world restoration and financial compensation are separate
operations with their own verification.
