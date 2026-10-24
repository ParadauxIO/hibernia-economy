---
title: Permissions & roles
order: 2
description: Who can do what — firm roles and their permissions, members vs. authorizers, and staff-only commands.
---

# Permissions and roles

A lot of commands check whether you're *allowed* before they do anything. If a command
seems to do nothing, the usual reason is that you don't have access yet. Here's how
access works.

## Firm roles

Inside a **[firm](/docs/guides/setting-up-a-business)**, every employee has one
**role**, and each role carries a set of **permissions**. There are four permissions:

| Permission | Lets the holder… |
|---|---|
| `ADMIN` | Manage staff/roles and authorized spending; not proprietor-only account controls |
| `FINANCIAL` | Use financial commands; spending also requires authorization on the source account |
| `CHESTSHOP` | Create and use ChestShops that belong to the firm |
| `DEFAULT` | Nothing special — just be a member |

Firms start with five roles: **Proprietor** (rank 1, `ADMIN`), **Co-Proprietor**
(2, `ADMIN`), **Manager** (3, `FINANCIAL`), **Supervisor** (4, `CHESTSHOP`), and
**Employee** (5, `DEFAULT`). New hires join as Employee. You can rename these, change
what they grant, or [build your own](/docs/guides/hiring-staff#build-your-own-roles).
The firm's **owner** (proprietor) can always do everything, regardless of roles.

Roles also have a **rank**, which is what `promote` and `demote` move people through —
the **lower** the rank number, the more senior (rank 1 is the top).

## Members vs. authorizers

Shared accounts — both **[business](/docs/concepts/accounts-and-money#business-accounts)**
and **[government](/docs/concepts/accounts-and-money#government-accounts)** — use the same
three access tiers, **viewer < member < authorizer**. What each tier may do differs a
little by the kind of account:

- A **viewer** is always read-only: they can see the account's balance and transactions
  but never spend or manage it. (The read-only tier is used on government accounts.)
- On a **firm** account, **members** can *see* the books and **authorizers** can *spend*.
  Staff are kept in step with their roles automatically — `ADMIN`-role staff become
  authorizers (view *and* spend), `FINANCIAL`-role staff become members (view only) — and
  the firm **owner** is the one who adjusts members and authorizers.
- On a **government** account, **members** can view *and* spend, and **authorizers** can
  manage viewers/members only with `treasury.gov.account.manage`. Managing
  authorizers requires `treasury.gov.admin`; both need the base `treasury.gov` node.

The common thread: each tier sees at least as much as the one below it, and the rights to
*spend* and to *manage access* are kept to a trusted few.

## Staff and operator commands

Some commands aren't for everyone:

- **Government** commands require their permission nodes and the appropriate
  department-account relationship, unless a staff override applies.
- **Operator** commands — like `/eco` (adjusting balances) and `/tax` — are for server
  admins and won't appear for normal players.

Throughout the reference, these are flagged with notes like "needs the ADMIN role" or
"operators only".

## Website permissions

Explorer roles are separate from firm roles and in-game command permissions.
Everyone signed in receives the configured **player** baseline. Initially it lets
you read your own information and accounts shared with you directly or through a
synchronized LuckPerms group. Firm membership exposes the roster; proprietor or
active FINANCIAL/ADMIN access is needed for firm financials.

The initial **government** role adds department records for related accounts;
**moderator** permits global read-only investigation; **admin** includes
administrative actions. Administrators can change these bundles or create new
roles, and roles combine. A role name alone does not guarantee an action: the
stored permissions and, where required, account relationship determine access.
The retired website `viewer` role now means player, not global investigation.

Public balances and aggregate market data stay public. Private histories may be
read by authorized staff as well as their owners and shared-account users. Linking
your Minecraft identity lets Explorer check those relationships; it does not
grant a staff role. See [Using the Explorer](/docs/guides/using-the-explorer).

## Where to go next

- Put roles into practice: **[Hiring & managing staff](/docs/guides/hiring-staff)**.
- How accounts hold the money these permissions guard:
  **[Accounts & money](/docs/concepts/accounts-and-money)**.
