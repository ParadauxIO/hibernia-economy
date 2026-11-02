---
title: Government account access
order: 4
description: How government account permissions work — granting access to individual players and to LuckPerms groups.
---

# Government account access

Government **departments** run on shared accounts (`/government …`, alias `/gov`). This
page is the operator's view of how access to those accounts is granted and what the
permission nodes mean. The player-facing basics are in
[Government accounts](/docs/guides/government-accounts).

## Levels of access

Every government account has three tiers of access (`VIEWER` < `MEMBER` < `AUTHORIZER`):

- **Viewers** can **view** the account and its history only — no spending, no managing
  access.
- **Members** can do everything a viewer can **and spend** from it (`/government pay`,
  `/government payout`).
- **Authorizers** can do everything a member can. With `treasury.gov.account.manage`
  they can manage viewers/members; only `treasury.gov.admin` can manage authorizers.

Each tier includes the ones below it. Viewing is gated by viewer access, spending by
membership, and managing access by authorizer status.

> [!NOTE]
> The same gate covers **fines**: to issue or revoke a fine paid into an account
> (`/fine issue <account> …`), a player must be a member or authorizer of that account
> (or an admin). See the [Treasury reference](/docs/reference/treasury#fines).

## Granting access two ways

Access can be granted to **individual players** or to a **LuckPerms group**. Most accounts
should use groups — see [why groups](#prefer-groups) below.

### Individual players

```text
/government account viewer add <account> <player>
/government account viewer remove <account> <player>
/government account member add <account> <player>
/government account member remove <account> <player>
/government account auth add <account> <player>
/government account auth remove <account> <player>
```

### LuckPerms groups

```text
/government account viewer addgroup <account> <group>
/government account viewer removegroup <account> <group>
/government account member addgroup <account> <group>
/government account member removegroup <account> <group>
/government account auth addgroup <account> <group>
/government account auth removegroup <account> <group>
```

`<group>` is a LuckPerms group name (e.g. `senator`, `treasury-staff`). List who has
access with:

```text
/government account viewer list <account>
/government account member list <account>
/government account auth list <account>
```

> [!NOTE]
> Managing **viewers** is gated like managing members — it needs
> `treasury.gov.account.manage` (plus authorizer access on the account).

Authorizer grants require an existing member grant for that same player/group.
Add the member first, then the authorizer; verify each list afterward.

### Prefer groups

Group access is resolved **dynamically** through LuckPerms: a player counts as a member
or authorizer if they are individually listed **or** they belong to any group granted on
the account. That means:

- Add the `senator` group as an authorizer once, and **everyone currently in that group**
  can spend — no per-person setup.
- When someone joins or leaves the group in LuckPerms, their account access **follows
  automatically**. You never have to revoke a departed official by hand.

So tie an account's access to the LuckPerms group that represents the department or rank,
and let group membership do the rest. Reserve individual `add` for one-off exceptions.

> [!CAUTION]
> Because group access is dynamic, anyone you add to a granted LuckPerms group **gains
> that account's spending rights immediately**. Audit which groups are authorizers on
> sensitive accounts before changing group membership.

## Permission nodes

Running these commands is itself gated by Treasury permission nodes (defaults shown;
`op` = operators, `false` = nobody until granted):

| Action | Node | Default |
|---|---|---|
| Use `/government` at all | `treasury.gov` | `op` |
| View an account / list members / list authorizers | `treasury.gov.account.view` | `false` |
| Add/remove **members** (incl. groups) | `treasury.gov.account.manage` *(and authorizer access on the account)* | `false` |
| Add/remove **authorizers** (incl. groups) | `treasury.gov.admin` | `op` |
| Create an account | `treasury.gov.account.create` | `false` |
| Archive an account | `treasury.gov.admin` | `op` |
| Spend from **any** account, server-wide | `treasury.gov.account.transfer` | `false` |

Two things to note:

- **Managing members vs. authorizers is intentionally asymmetric.** A department head with
  `treasury.gov.account.manage` and authorizer access can add and remove ordinary members,
  but **only a `treasury.gov.admin` operator can add or remove authorizers**. This keeps
  the "who can grant spending power" decision with staff.
- **`treasury.gov.account.transfer` is a blanket grant** to spend from *every* government
  account. Don't hand it to department staff — add them as a member or authorizer of their
  specific account instead. It exists for trusted automation/admins.

The full node list (including the everyday `/government` reads) is in the
[Treasury reference](/docs/reference/treasury#permission-nodes).

## Creating and retiring accounts

```text
/government account create <name>     # needs treasury.gov.account.create
/government account archive <name>    # needs treasury.gov.admin
```

The primitive accounts Treasury creates on startup (starting balances, `Eco`,
`DCGovernment`, `GovernmentFines`) are managed automatically — don't archive those.

## The explorer's government workspace

The explorer shows government money at `/gov`. Who sees what follows the access above,
and nothing else:

- A role with `government.read` opens the workspace. Its scope is the government
  accounts the player owns or can read (VIEWER and up, directly or through a LuckPerms
  group). Give a department its accounts in game and its officials see them on the
  explorer.
- `government.read.all` puts every government account in scope.
- `government.audit.read` adds the Audit page: who moved money in and out of those
  accounts, and who viewed them on the explorer.

Reads made through an account relationship are recorded in the explorer audit log.

### Government units

Units group government accounts into departments, ministries, cities or states for
the workspace. They are presentation only and never grant access: an official sees a
unit's figures for the accounts they can already read, with **Partial view** when they
can't read all of them.

Manage them at **Admin → Government units** (`/admin/gov-units`, needs
`gov_units.manage`):

1. **New unit**: give it a name and a slug (used in links such as `/gov?unit=commerce`).
   Pick a parent to build a hierarchy (nation → department, or state → city).
2. In the accounts table, choose each account's unit. An account belongs to at most
   one unit; a parent's figures include its sub-units.
3. Deleting a unit leaves its accounts unassigned and moves its sub-units to the top
   level. No money or access changes.

Government accounts outside every unit appear under **Unassigned**.

### Compliance tools

`/gov/compliance` has a watchlist and a firm investigation view (accounts, money in and
out by counterparty, transfers with the proprietor, large and round-number transfers,
and sudden balance changes). They need `firm.financials.read.all` together with
`transactions.read`.

A **compliance** role with those permissions (plus `firm.financials.read` and
`market.sales.read`) is created for you but given to nobody. To give a department the
tools:

1. Open **Admin → Groups** and find (or create) the explorer group synced from the
   department's LuckPerms group.
2. Add the **compliance** role to that group.

Membership then follows LuckPerms: whoever is in the in-game group gets the tools, and
loses them when they leave it. Nothing in the explorer is tied to a particular group,
department or server.

## See also

- [Government accounts](/docs/guides/government-accounts) — the player-facing guide.
- [Operator commands](/docs/admin/operator-commands) — `/eco`, `/tax`, and salaries.
- [Treasury reference → Permission nodes](/docs/reference/treasury#permission-nodes).
