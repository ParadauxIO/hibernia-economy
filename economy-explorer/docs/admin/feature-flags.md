---
title: Release feature flags
order: 22
description: Enable or disable release features across this economy.
---

# Release feature flags

Open **Admin → Feature flags** to manage features that developers have registered
for controlled release. Each value applies to every game server, API and Explorer
using this economy's database. A flag starts disabled; deploying a release does
not enable it automatically.

You need `feature_flags.read` to view the list and `feature_flags.update` to change
values. These grants are separate from permission to use the enabled feature.

Explorer can deploy up to 12 hours before the plugins apply database migrations.
Keep migration-dependent flags disabled during this gap, so existing behavior
continues. A successful Explorer deployment does not mean the database is ready.
The flag list itself requires the initial V52 migration.

1. Read the flag's description. Confirm that the plugin deployment has completed,
   Treasury has successfully applied the required migrations, and any required
   backfill and compatible consumer deployments are complete. Migration success
   leaves the flag disabled until an operator explicitly enables it.
2. Select **Enable** or **Disable**.
3. Check the saved state and last-change details. Allow five seconds plus query
   time for active services to observe the change.
4. Check the feature's actual behavior. The page shows the stored value, not
   an acknowledgement from each running server.

If another operator changed the flag while your page was open, the save is
rejected. Refresh and review the current value before trying again. Changes are
recorded in the existing audit trail; audit storage failures follow the existing
fail-open audit policy.

Disabling stops new operations after propagation. Transactions already underway
finish normally, and past changes are not rolled back. Services without a fresh
flag snapshot disable features after 30 seconds; they resume the saved state when
the database connection recovers. Enabling does not replay skipped scheduled jobs.

An empty list means this database has no registered release flags. Developers add
new definitions through migrations. For command-line and SQL procedures, see the
repository's `wiki/feature-flags.md` runbook.
