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
contains the V15 cutover preflight SQL and rollout holds; use its version at your
release SHA. Its historical timings and starting schema are not universal targets.

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
   space/time for V36's `ledger_txn_volume` backfill and rollback. Use current row
   counts and measured rehearsal timings.
3. Build the release bundle from the selected SHA (`./gradlew release`), record
   image tags and retain the prior compatible artifacts/configuration.
4. Hold automatic deployment/image updates. Stop game servers, REST and Explorer
   for every tenant affected by the shared images. Confirm no application writers
   remain, then take and verify the coordinated backups.
5. Run **Economy Flyway · Migrate** for the confirmed target, selecting the release
   revision and matching `confirm_target`. Existing managed databases should not
   be baselined. Require all expected migrations successful and none pending.
6. If all tenants are ready, replace plugin jars and deploy matching images/configs.
   Apply any coordinated credential changes and [SERVICE renewal](/docs/admin/access-and-credentials).
   Start dependencies and applications, then perform the checks below.

Old and new versions are not necessarily schema-compatible. In particular the
V15 upgrade replaces/drops tables and invalidates stored-token assumptions; it
requires the full-stop procedure, not a rolling mix of old and new binaries.

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
