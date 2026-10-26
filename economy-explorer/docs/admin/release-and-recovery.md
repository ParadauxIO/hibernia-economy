---
title: Release, backup and recovery
order: 9
description: Prepare and verify releases, schema upgrades, backups, restores, rollbacks, and isolated staging refreshes.
---

# Release, backup and recovery

Choose one release SHA and a compatible set of plugin jars, REST/Explorer images,
configurations and schema migrations. Record the current and target versions for
**every tenant**. The detailed repository
[release runbook](https://github.com/ParadauxIO/hibernia-economy/blob/develop/wiki/release-runbook.md)
contains the coordinated cutover through V42 and rollout holds; use its version at
your release SHA. V15 is a historical baseline, not a verified current tenant state.
The plugin bundle requires Java 25 and Paper 26.3. Verify installed integrations
against that runtime before choosing the maintenance window.

## Current migration scope

Inspect each tenant's history to determine which scripts are pending:

| Versions | Changes to account for |
|---|---|
| V16–V22 | Firm fines, shared player directory, unified account access and read-access views; legacy access/player tables are removed. |
| V23–V29 | Stored-token erasure, SYSTEM constraints, native shop discovery/preferences, proprietor audit and fine deduplication. |
| V30–V35 | Explorer roles/relationships, published tax configuration, explicit firm-name release, creation locks and shop aliases. |
| V36–V39 | Transaction-volume aggregates, Business/Realty group sources, taxation index and employment history. |
| V40–V41 | Withdrawn; these version numbers remain retired. Global name reservations are not part of this release. |
| V42 | Stable SYSTEM plugin-routing keys, independent of editable wallet labels. |

**A target that applied V40/V41 needs a pre-V40 restore.** These migrations ran only
on staging. Stop staging writers, rebuild from a verified pre-V40 backup and apply
the remaining scripts through V42 with the matching bundle. Confirm that neither
the withdrawn history nor namespace tables, routines or triggers remain. Deleting
history rows or running Flyway `repair` does not remove their enforcement. See the
[staging rebuild procedure](https://github.com/ParadauxIO/hibernia-economy/blob/develop/wiki/name-namespace.md#staging-rebuild).

V42 must capture original SYSTEM identities before display labels are renamed.
Verify the plugin-to-account mappings, IDs, owners and balances before and after
migration. Do not infer a missing original key from an already-renamed label or
combine wallets to resolve a conflict. The optional preparation procedure and its
DDL/rollback constraints are in
[SYSTEM wallet routing](https://github.com/ParadauxIO/hibernia-economy/blob/develop/wiki/system-wallet-routing.md).

## Backup and restore prerequisites

Inventory the economy schema, Realty/legacy databases where used, LuckPerms,
worlds/WorldGuard regions and plugin files. Preserve ChestShop `users.db`, item-code
stores, configs, custom-item definitions and recovery evidence. Record app versions
and secret-store versions securely. Database-only backups cannot restore lost
physical shop items or region state.

Pause writers for a coordinated game/database snapshot. A `--single-transaction`
dump gives an InnoDB database snapshot; it does not make multiple databases and
world files atomic together. Include triggers, routines and events according to
your environment's backup policy, and check command exit status, checksum, size
and object inventory. Do not print credentials or store dumps in the repository.

Rehearse restoring to an isolated target with outbound integrations disabled.
Check schema history, tables/views/triggers/routines, the
[money invariants](https://github.com/ParadauxIO/hibernia-economy/blob/develop/ops/money-invariants.sql),
and representative account/firm/shop/property records. A file existing is not a
successful restore test. Define acceptable data loss and recovery time with the
server owner before a production maintenance window.

## Schema-changing release

1. Run **Economy Flyway · Info (read-only)** for each tenant against the intended
   release. Reconcile checksum/version problems before proceeding. Never use
   `baseline`, `repair` or `clean` as a generic fix for a failed migration.
2. Rehearse migrations and application acceptance on an isolated snapshot. Allow
   space/time for V36's `ledger_txn_volume` backfill and V42's index replacement.
   Measure the full upgrade and restore; the older V16–V36 rehearsal does not
   establish V42 timing or readiness.
3. Build a local rehearsal bundle from the selected SHA (`./gradlew release -Pci=true`).
   Production publication uses **Economy · Release plugins and APIs**. Retain its
   `release/manifest.json`, plugin hashes, immutable image tags/digests and the prior
   compatible artifacts/configuration.
4. Hold automatic deployment/image updates. Stop game servers, REST and Explorer
   for every tenant affected by the shared images. Confirm no application writers
   remain, then take and verify the coordinated backups.
5. Run **Economy Flyway · Migrate** for the confirmed target, selecting the release
   revision and matching `confirm_target`. Existing managed databases should not
   be baselined. Require all expected migrations successful and none pending.
6. If all tenants are ready, replace plugin jars and deploy matching images/configs.
   Coordinate the planned signing-secret rotation across TreasuryAPI and REST,
   [renew the Explorer SERVICE credential](/docs/admin/access-and-credentials), and
   arrange personal/business token reissue. Existing tokens stop working after
   rotation; newly issued tokens are shown once and must be copied securely.
   Start dependencies and applications, then perform the checks below.

Old and new versions are not necessarily schema-compatible. In particular the
upgrade from the historical V15 baseline replaces/drops tables and invalidates
stored-token assumptions. The cutover requires a full stop. After a SYSTEM label
rename, old code would route using the label; a JAR-only rollback is insufficient.

## Acceptance before reopening

Check startup logs and migration state; run money invariants and compare expected
record counts. On staging, exercise login/linking, own/shared/denied histories,
transfer balances and ledger postings, firm staff/account access, shop buy/sell
with tax and metadata, and property operations. Check `/tax status`, published
rates, LP reconciliation and controlled webhook delivery. In production perform
authorized smoke checks and inspect existing financial state; do not trigger tax
or a broad payroll merely to test readiness.

Resume jobs/replicas/GitOps only after compatible services pass. Record the final
versions, verification evidence, backup location and any follow-up concerns.

## Failed migration or rollback

Keep writers stopped. Save the failed migration name, logs, schema history and
current snapshot for investigation. MariaDB DDL can partially commit: rerunning
or marking a migration repaired can conceal an incomplete schema.

Use either a reviewed forward repair or restore the verified pre-change backup
with the matching old binaries, configuration and world/plugin state. A code-only
rollback is insufficient after destructive schema changes. Verify invariants and
application journeys on the restored state before reopening. If writes resumed
after the backup, inventory those transactions first and agree how to preserve or
reconcile them; do not silently overwrite new financial activity.

## DemocracyCraft staging refresh

Follow the guarded
[staging-refresh procedure](https://github.com/ParadauxIO/hibernia-economy/blob/develop/ops/dev-server/staging-refresh.md)
for `ops/dev-server/refresh-staging.py`. It copies **four** databases: economy,
ChestShop, Realty and LuckPerms. Older three-database manifests are invalid.

1. Create a private snapshot with the supplied export tool; retain its manifest and
   checksums. Separate database dumps are not a cross-database atomic snapshot.
2. Record replica counts and explicit cluster context; pause the deployment agent,
   GitOps/autoscaling and API/Explorer writers. Start the clone's guarded DB-only
   mode at localhost:33306.
3. Restore using the provided command. It validates the target, saves a rollback
   snapshot, sanitizes outbound hooks/link identities/API keys, migrates using the
   deployed plugin commit and runs invariants. It preserves the existing valid
   staging SERVICE identity; rotate an expired token in its authoritative store first.
4. Require `restore-result.json` and successful checks. On **success and failure**
   writers remain paused. Shut down DB-only mode, resume the agent and restore
   recorded replica/GitOps settings only after verification.
5. Check transfer, firm, shop, property and LP groups; verify no production webhook
   destinations remain. The copy still contains player/history data and must stay
   restricted; sanitization is not general anonymization.

Use the tool's documented `restore --rollback` path while writers stay paused if
recovery is needed. Restore matching worlds/configuration separately. Do not use
Flyway staging-clean for a refresh or point these commands at production.
