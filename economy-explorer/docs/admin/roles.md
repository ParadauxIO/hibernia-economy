---
title: Managing Explorer access
order: 2
description: Configurable roles and permissions, account relationships and LuckPerms sync.
---

# Managing Explorer access

A role determines **what** someone may do. Their relationship to an account or firm
determines **where** they may do it. Linking connects the website login to the
Minecraft UUID used by both checks.

To explain a user's current access, use
[Role grant provenance](/docs/admin/role-provenance). It shows direct, baseline and
group-derived role sources, including synchronization freshness. It requires both
`roles.read` and `groups.read`; it is not an immutable historical audit trail.

These are the initial role settings; administrators can change their permissions.

| Role | Initial access |
|---|---|
| **player** | Default for signed-in users. Own information, owned accounts, directly shared accounts and accounts shared with an LP group they belong to. Firm members see the roster; proprietors and active FINANCIAL/ADMIN staff see firm financials. |
| **government** | Player access plus the government workspace (`/gov`) for government accounts they own or have direct/group read access to, including its Audit page (`government.audit.read`). |
| **compliance** | Not given to anyone by default. Every firm's financials, the global ledger and sales attribution, for the compliance tools. Link it to a group to use it ([Government account access](/docs/admin/government-accounts#compliance-tools)). |
| **moderator** | Read-only investigation of all accounts, firms, transactions and sales, with privileged reads audited. No administrative controls or writes. |
| **admin** | Every permission, including administration of access, accounts, firms, API keys, webhooks and government units (`gov_units.manage`). |

Roles combine. Government is not a rank above player or below moderator: someone
may hold both government and moderator. Public balances and aggregate market data
remain public. Account membership grants read access on the explorer, regardless of
whether the in-game relationship is VIEWER, MEMBER or AUTHORIZER; it does not grant
website administration or access to unrelated accounts.

Account read access also allows opening individual transactions involving that
account, including government accounts. Transaction details show all postings
and counterparties so readers can trace where money went. This does not grant
access to the counterparty's other transactions or private account history.
The whole-economy transaction list still requires `transactions.read`.

The former `viewer` role (including its older `staff.audit` spelling) now means
`player`. Assign `moderator` explicitly to staff who need global investigation.
Government no longer grants global player or firm oversight.

## Configure roles and permissions

Open **Admin → Roles** to create a role with a stable key, display name,
description and selected permissions. Open an existing role to edit its bundle
or delete it. Deleting a role removes every direct and group assignment;
recreating the same key starts with no assignments. The player baseline cannot
be deleted, but all its permissions can be edited or removed.

Permissions are individual operations. For example, a support role can have
`account.admin.read` and `account.rename` without permission to move money,
change ownership or archive accounts. `api_keys.read` does not allow key
revocation, rotation or rate-limit changes. `account.read` still needs a resource
relationship; `account.read.all` explicitly allows unrelated account histories.

Grant the corresponding read permission to show an admin tool, then select only
the actions that operator needs. Role/group viewing, creation, deletion, membership,
role assignment and LP source changes are separate permissions. The editor lists
every permission with its scope. Global write permissions apply to any resource
of that type, so assign them deliberately.

Operators can only grant roles whose permissions they hold. That restriction
also applies to editing existing roles and changing membership or LP sources of
a privileged group. Concurrent edits are checked, and stale role edits must be
refreshed. Changes take effect on the next request without signing in again.
An admin role name is not a bypass: its stored permissions are authoritative.
Keep an independent administrator for recovery before changing your own access.

## Direct roles

On a role’s detail page, add or remove direct assignments by player name or UUID.
The server commands also accept any configured role key. The in-game
`treasuryapi.ui.user` permission (or console access) is a privileged recovery
authority: it can assign any configured role independently of website permissions.
Reserve it for trusted server administrators:


```text
/treasuryapi ui user add <role-key> <name>
/treasuryapi ui user remove <role-key> <name>
/treasuryapi ui user list <name>
```

Player is the baseline, so removing an explicit player grant does not disable
someone's baseline permissions. Removing a permission from the player role changes that baseline for everyone. Removing one role grant also does not remove that same
role if another group still grants it.

## Roles from groups

On **Admin → Groups**, create a group, select its roles, then add manual members
or configure a LuckPerms source. Bare names (`moderator`) and `group.moderator`
refer to LP group inheritance. Dotted permission nodes use effective permission
checks, including inherited grants and explicit denies.

To give a department a set of tools, link its group to a role. For example, to give
the Department of Commerce the compliance tools: open the explorer group whose
LuckPerms source is the department's group (say `group.doc`), tick the
**compliance** role under **Roles this group grants**, and save. Everyone in that
LuckPerms group then gets the role on their next sync, and loses it when they leave
the group. The group page shows the source node and its live member count.

Sync resolves online and offline players using the server's static context.
World-specific or another server's contextual grants do not become global web
permissions. Manual membership survives LP sync and can coexist with synced
membership for the same player.

Account LP groups in Treasury's account access settings are synchronized too;
they do not need a duplicate explorer group. Being in an account's LP group grants
that account's read relationship, not an explorer staff role.

Enable `reconciliation.enabled: true` in existing plugin configurations. Sync runs at startup and at most 30 minutes apart. Players can run
`/treasuryapi ui sync` for immediate reconciliation. Successful empty results revoke
old memberships. Failed lookups do not publish partial results; unrefreshed LP grants
expire after one hour. Changing or clearing an explorer group's LP source removes
its old synced memberships immediately; manual grants remain.

### Business and Realty memberships

Server operators can also map a group to a Business firm or a Realty region.
A firm mapping includes its proprietor and employees with **FINANCIAL** or
**ADMIN** permission. A region mapping includes its title holder and current,
unexpired tenant. Offline players are included. The group's selected roles apply
to those members just as they do to manual members.

Set these on the group's page under **Membership sources** (needs `groups.sync.update`):
the **Firm** source takes a firm ID, and the **Region** source takes
`world-uuid/region-id` (the region ID is case-sensitive). Each source shows how many
of its members are live now. The group member list shows which source manages each
membership. Business, Realty, LuckPerms and manual
memberships can coexist; removing one source does not remove another's grant.

Business and Realty refresh on the scheduled interval independently of LuckPerms.
They also expire after one hour without a successful refresh. Changing or removing
a mapping invalidates its old grants immediately. The `/treasuryapi ui sync`
command refreshes LuckPerms only; allow the next scheduled pass for firm/property
changes. Operators must apply database migration V37 before deploying this feature.
