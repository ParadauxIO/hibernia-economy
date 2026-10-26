---
title: Admin overview
order: 1
description: Staff-only documentation for running the economy and the Explorer.
---

# Admin documentation

This section is for **server staff** with Explorer admin access. It covers the tasks
that keep the economy and this site running — granting access, adjusting balances, and
the automated systems.

> [!NOTE]
> These pages require `docs.admin.read`. Access to a procedure does not grant
> permission to perform it; each tool/command checks its own permissions.

Start with the **[Admin-operator runbook](/docs/admin/operator-runbook)** for the
routine checklist and procedures by task.

## In this section

- **[Service configuration](/docs/admin/config-services)** — TreasuryAPI, Explorer, REST, identity, caches and monitoring.
- **[Explorer administration](/docs/admin/explorer-administration)** — player investigations, account/firm changes and audit evidence.
- **[Access and credentials](/docs/admin/access-and-credentials)** — login recovery, keys, issuer limits and SERVICE-token rotation.
- **[Realty operations](/docs/admin/realty-operations)** — party/schema upgrades, lease controls, modules and property previews.
- **[Incident procedures](/docs/admin/incidents)** — ledger, tax/payroll, Business, ChestShop and Realty diagnosis.
- **[Release and recovery](/docs/admin/release-and-recovery)** — deployment, backups, migrations, rollback and staging refresh.

- **[Managing Explorer access](/docs/admin/roles)** — grant or revoke admin, government,
  moderator and custom-role access for the website.
- **[Role grant provenance](/docs/admin/role-provenance)** — search the sources of a
  user's roles and inspect synchronization freshness.
- **[Vault bridge analytics](/docs/admin/bridge-analytics)** — understand estimated
  volume/counts and why they differ from raw ledger history.
- **[Government account access](/docs/admin/government-accounts)** — grant access to
  department accounts by individual player or LuckPerms group, and the permission nodes
  behind it.
- **[Operator commands](/docs/admin/operator-commands)** — `/eco` balance adjustments,
  `/tax` cycles, and where salaries are configured.
- **[Plugin configuration](/docs/admin/configuration)** — every config setting across
  Treasury, Business, ChestShop, and Realty, including the **property-tax formula** and
  the income/balance taxes.
- **[Webhooks](/docs/admin/webhooks)** — list, search, and manage every webhook on the
  server, and register one for any account.

> [!CAUTION]
> Operator commands move or create money and grant access. They're powerful and
> logged — use them deliberately, and prefer the least-privileged option that does the
> job.
