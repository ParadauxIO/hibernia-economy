---
title: Realty operations and upgrades
order: 16
description: Operate account/group parties, lease changes, modules and preview services, and upgrade Realty's separate schema safely.
---

# Realty operations and upgrades

Realty is a separately versioned submodule. Match the plugin, modules, REST and
property website to the revision pinned by your economy release. The detailed
[2.0.0 upgrade notes](https://github.com/MCCitiesNetwork/realty/blob/95eba03c1041f57e79f9f0a659d9d392d0f2ddab/docs/release-notes/2.0.0.md)
include the legacy-party migration queries; read them before changing a live
Realty database. [Commands](/docs/reference/realty) and
[configuration](/docs/admin/config-realty) describe the pinned behavior.

## Upgrade from the old player-UUID model

1. Record versions, database, defaults, account IDs and legacy government UUIDs.
   Stop Realty REST and the game server; back up Realty, Treasury and matching
   worlds/regions. Realty-only restoration after new payments would leave
   Treasury and property state inconsistent.
2. Replace the Realty plugin and **all** `plugins/Realty/modules` jars together.
   Keep ordinary players out during startup/migration and verification.
3. Start the plugin and require successful Realty migration V19 (`parties`).
   This is separate from economy Flyway. If it fails, stop and restore the saved
   compatible state; MariaDB cannot undo earlier DDL automatically.
4. Start matching Realty REST only after migration: version 2.0.0 requires schema
   19. Old REST or query-service modules are not compatible.
5. Follow the linked migration's reviewed manual procedure to map legacy government
   UUIDs to the **verified Treasury government account IDs**. A wrong but valid
   government account can silently redirect money and authority. Verify contract
   and history destinations before reopening. Scheduled termination refunds can
   still run during this phase; inspect and reconcile any affected payments.
6. Set new `default-freehold-authority`, `default-leasehold-landlord` and optional
   `default-freehold-titleholder` objects. Old UUID keys are ignored; packaged
   defaults name `GovSecurity`, which may not exist on your tenant. Reload and
   verify create/register with intended parties.
7. Review group mappings, permissions and `messages.yml`. Existing help lists are
   not automatically replaced: update stale `help.management` from the generated
   `plugins/Realty/defaults/default-messages.yml`.
8. Exercise representative rental/payment/refund, account-party authority, group
   mapping, denied access and property REST responses before restoring player
   admission. Retain prior artifacts and coordinated backups for rollback.

## Account and permission-group parties

Landlords/authorities can be player, government/business/system account or mapped
permission-group parties. Titleholders/tenants stay players. Check `/realty group
list`; map using `/realty group map <group> <account> --government` (or the proper
business/system flag). Unmapping is refused while contracts **or history** refer
to the group. Remapping changes the funding/management account: record the old
mapping and verify the new account before doing it.

Accounts must be live, non-personal and not require authorization for every
transfer. `account-managers: members` accepts direct members/authorizers;
`authorizers` narrows it. Treasury LP account-group grants are not read by Realty,
so a government permission working in-game does not by itself establish Realty
party access. Group parties need Vault's permission service; non-player parties
need Treasury. Check actual allowed and unrelated users.

Anyone with `realty.command.set.authority` may change any freehold's authority;
there is no `.others` gate for that command. Review that grant carefully.
Create/register permissions allow any resolvable named party. Conflict-of-interest
checks restrict trading on land whose authority the actor represents; the staff
bypass does not permit self-dealing.

## Lease changes and permissions

`set price`, `set duration`, `set maxextensions` and `unset price` now default to
available, with holdership enforced in the database. Remove stale
`modify.price|duration|maxextensions` and `.leasehold` grants/denials, replacing
them with the corresponding `set` nodes where needed.

A landlord's occupied-lease term change waits until renewal; a tenant's request
needs landlord acceptance. `.others` bypasses holdership only. Immediate changes
require `--now` and `realty.command.set.now`; holder changes on occupied leases,
unlimited extensions and changes during termination need that path. Inspect
pending proposals and the next-renewal result, not just the command response.

For termination, record notice date, any extension charge and expected refund.
`terminate --now` has its own permission and ends immediately; ordinary
termination honors the configured notice. `rentable false` blocks new rentals
without evicting the current tenant. Verify refunds and property state separately.

## Modules, REST and preview operations

Use `/realty module list` and `/realty module reload <module>` with compatible
jars. Verify the query-service and REST contracts together: party fields now have
`kind`, `id`, `name`; party regions are at `GET /v1/parties/{kind}/{id}/regions`.
The `authority` listing category is separate from leasehold `landlord`. A stale
client interpreting the old category can show the wrong land without an error.
The separate [Realty REST guide](https://github.com/MCCitiesNetwork/realty/blob/95eba03c1041f57e79f9f0a659d9d392d0f2ddab/realty-web/realty-rest/README.md)
and [property web guide](https://github.com/MCCitiesNetwork/realty/blob/95eba03c1041f57e79f9f0a659d9d392d0f2ddab/realty-web/README.md)
cover service-specific configuration; these are not Economy Explorer routes.

Preview capture is player-only, with membership/override, cooldown and volume
checks. Set the blocks-per-tick budget for the server; `--force` bypasses cooldown
only. Verify the preview after capture and inspect DB size/packet errors on failure.
The stored hollow exterior format has no restore command and is not WorldEdit
compatible. Keep actual world backups independently.
