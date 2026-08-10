---
title: Business
order: 2
description: Every /business command — firms, staff, roles, accounts, money, and tax.
---

# Business

**Business** lets players form companies — *firms* — that can hold money, employ other
players, and own ChestShops. It turns a solo player's wallet into a company with shared
accounts, defined roles, and staff. It works hand-in-hand with
[Treasury](/docs/reference/treasury), which holds the actual money.

**Made by** Paradaux, built in-house for the Minecraft Cities Network (DemocracyCraft &
StateCraft) so players can run real businesses inside the economy.

Everything you can do with a firm is below. The base command is `/business`, and it also
answers to `/firm`, `/company`, `/db`, and `/democracybusiness`.

> [!NOTE]
> `<angle brackets>` are required, `[square brackets]` are optional — don't type the
> brackets. Firm names are a **single word** of letters and digits (you can also use
> `_`, `.`, or `-`) — e.g. `Acme`, `RedStone`, `North_Mining`. Spaces and other symbols
> are stripped automatically, so they won't end up in the name. A name is 2–32 characters
> and **can't start with a digit**. Where a command asks for `<accountId>`, use an ID from
> `/business account list`.

## Firms

| Command | What it does |
|---|---|
| `/business create <firm>` | Create a firm — you become its owner. Sets up starter roles and a corporate account. |
| `/business info <firm>` | Show a firm's owner, balance, HQ, and details. |
| `/business list` | List the firms you're part of. |
| `/business list <player>` | List a player's firms. |
| `/business list all [page]` | List every firm on the server. |
| `/business disband <firm>` | Ask to close a firm you own — prompts for confirmation first. |
| `/business disband <firm> confirm` | Confirm and actually close the firm. Its money returns to you and the firm is archived. |
| `/business attribute set hq <firm> <plot>` | Set the firm's headquarters to a plot you own. |
| `/business attribute set discord <firm> <url>` | Set the firm's Discord invite link. |

```text
/business create Acme
/business info Acme
/business disband Acme
/business disband Acme confirm
```

> [!CAUTION]
> `disband` is permanent. Make sure the firm's accounts are emptied the way you want
> first — see [Accounts](#accounts).

### Staff overrides

A handful of commands let server staff (operators / the DOC) act on **any** firm,
bypassing firm roles and proprietorship entirely. These are operator-only and meant for
moderation — see [Permission nodes](#permission-nodes).

| Command | What it does |
|---|---|
| `/business admin disband <firm>` | Force-disband a firm (no owner confirmation). |
| `/business admin rename <firm> <newname>` | Rename a firm. |
| `/business admin set hq <firm> <plot>` | Set any firm's HQ plot. |
| `/business admin set discord <firm> <url>` | Set any firm's Discord invite. |
| `/business admin set proprietor <firm> <player>` | Reassign a firm's proprietor. |

## Staff

| Command | What it does |
|---|---|
| `/business offer <firm> <player>` | Offer someone a job (alias `/business hire`). Expires in 5 minutes. |
| `/business offer rescind <firm> <player>` | Take back an offer you sent. |
| `/business offer accept <firm>` | Accept a job offer made to you. |
| `/business offer reject <firm>` | Decline a job offer. |
| `/business staff list <firm>` | List the firm's employees. |
| `/business staff promote <firm> <player>` | Move an employee up one role. |
| `/business staff demote <firm> <player>` | Move an employee down one role. |
| `/business fire <firm> <player>` | Remove an employee (needs the ADMIN role). |
| `/business resign <firm>` | Quit a firm you work for. |

```text
/business offer Acme Steve
/business staff promote Acme Steve
```

See the **[Hiring & managing staff guide](/docs/guides/hiring-staff)** for the full
walkthrough.

## Roles

Roles group permissions and set the promotion ladder. A new firm starts with five
roles, each seeded with a permission:

| Role | Rank | Seeded permission |
|---|---|---|
| **Proprietor** | 1 | `ADMIN` |
| **Co-Proprietor** | 2 | `ADMIN` |
| **Manager** | 3 | `FINANCIAL` |
| **Supervisor** | 4 | `CHESTSHOP` |
| **Employee** | 5 | `DEFAULT` |

New hires join as **Employee**, and a **lower** rank number is more senior. The four
permissions are:

- **`ADMIN`** — full control: manage staff, roles, accounts, and money.
- **`FINANCIAL`** — spend from accounts they're an authorizer on (withdraw and pay out).
- **`CHESTSHOP`** — create and manage the firm's ChestShops (enforced by the ChestShop
  plugin).
- **`DEFAULT`** — a baseline marker that grants nothing on its own. It's auto-attached to
  every new custom role you create, and it's what the Employee role carries.

See **[Permissions & roles](/docs/concepts/permissions-and-roles)** for the full
breakdown.

| Command | What it does |
|---|---|
| `/business staff role list <firm>` | List the firm's roles and their ranks. |
| `/business staff role create <firm> <role> <order>` | Create a role at rank `<order>` (lower number = more senior). |
| `/business staff role rename <firm> <role> <newname>` | Rename a role. |
| `/business staff role delete <firm> <role>` | Delete a role. |
| `/business staff role permission add <firm> <role> <permission>` | Grant a permission to a role. |
| `/business staff role permission remove <firm> <role> <permission>` | Remove a permission from a role. |
| `/business staff role permission list <firm> <role>` | List a role's permissions. |

```text
/business staff role create Acme Treasurer 3
/business staff role permission add Acme Treasurer FINANCIAL
```

Managing roles needs the **ADMIN** permission.

## Accounts

A firm can hold several accounts. **Members** can view an account; **authorizers** can
spend from it.

| Command | What it does |
|---|---|
| `/business account list <firm>` | List the firm's accounts with IDs and shop codes. |
| `/business account create <firm> <name>` | Open a new account. |
| `/business account setdefault <firm> <accountId>` | Choose which account the short money commands use. |
| `/business account archive <firm> <accountId>` | Close an account (history is kept). |
| `/business account balance <firm> <accountId>` | Show one account's balance. |
| `/business account transactions <firm> <accountId> [page]` | One account's history. |
| `/business account members <firm> <accountId>` | List an account's members. |
| `/business account authorizers <firm> <accountId>` | List an account's authorizers. |
| `/business account addmember <firm> <accountId> <player>` | Let a player view the account. |
| `/business account removemember <firm> <accountId> <player>` | Remove a member. |
| `/business account addauthorizer <firm> <accountId> <player>` | Let a player spend from the account. |
| `/business account removeauthorizer <firm> <accountId> <player>` | Remove an authorizer. |
| `/business account sync <firm> <accountId>` | Re-sync members/authorizers to match staff roles. |

### Moving money (specific account)

| Command | What it does |
|---|---|
| `/business account deposit <firm> <accountId> <amount>` | Put your money into the account. |
| `/business account withdraw <firm> <accountId> <amount>` | Take money out (authorizer only). |
| `/business account pay into <firm> <accountId> <amount>` | Pay your own money into the account. |
| `/business account pay player <firm> <accountId> <player> <amount>` | Pay a player from the account. |
| `/business account pay business <firm> <accountId> <target> <amount>` | Pay another firm from the account. |

```text
/business account create Acme Wages
/business account pay player Acme 42 Steve 1000
```

> [!NOTE]
> Paying money **into** an account (`pay into`/`deposit`) is open to anyone. **Spending**
> — `withdraw` and any `pay` *out* of the firm — needs the **FINANCIAL** or **ADMIN**
> permission **and** being an authorizer on that account. Role sync makes `ADMIN`-role
> staff both members and authorizers automatically, but `FINANCIAL`-role staff are added
> as members (view) only — the proprietor must `addauthorizer` them before they can spend.

See the **[Business accounts guide](/docs/guides/business-accounts)** to put this
together, and **[ChestShops for your firm](/docs/guides/chestshop-business)** to route
shop sales into an account.

## Quick money (default account)

Shorthand that acts on the firm's **default** account, so you don't pass an ID:

| Command | What it does |
|---|---|
| `/business balance <firm>` | The default account's balance. |
| `/business deposit <firm> <amount> [memo]` | Deposit into the default account; an optional memo is recorded on the transaction. |
| `/business withdraw <firm> <amount>` | Withdraw from the default account. |
| `/business pay into <firm> <amount>` | Pay your money into the default account. |
| `/business pay player <firm> <player> <amount>` | Pay a player from the default account. |
| `/business pay business <firm> <target> <amount>` | Pay another firm from the default account. |
| `/business send <source> <target> <amount> [memo]` | Send money from one of your firms to another, with an optional memo. Run `/business send` with no arguments to list your firms and their balances first. |
| `/business transactions <firm> [page]` | History across all the firm's accounts. |

## Ownership transfer

Handing the firm to someone else is a confirmed, two-sided handshake:

| Command | What it does |
|---|---|
| `/business transfer begin <firm> <player>` | Start a transfer; gives you a code. |
| `/business transfer confirm <firm> <player> <code>` | Confirm it with that code. |
| `/business transfer complete <firm> <player>` | The new owner accepts and takes over. |
| `/business transfer cancel <firm> <player>` | Call off a transfer you started. |
| `/business transfer reject <firm> <player>` | Decline an incoming transfer. |

## Tax

| Command | What it does |
|---|---|
| `/business tax info <firm>` | Show the firm's tax status and estimated weekly tax. |
| `/business tax exempt <firm> <on\|off>` | Set or clear tax exemption (admin). Accepts any on/off token — `true`/`false`, `yes`/`no`, `on`/`off`, `1`/`0`, `enable`/`disable`. |

## Sales

Read-only reporting over the firm's ChestShop sales. Viewing is gated to the firm's
**proprietor** or staff with the **FINANCIAL** or **ADMIN** role — sales figures are the
firm's books, not public.

| Command | What it does |
|---|---|
| `/business sales <firm> [page]` | List the firm's recent ChestShop sales (paginated). |
| `/business sales summary <firm> [days]` | Aggregate sales report — totals, top items, top customers (default 30 days). |
| `/business sales export <firm> <days>` | Get an Economy Explorer link to the firm's sales report (capped by `sales.max-export-days`). |
| `/business sales toggle <firm>` | Turn real-time sale notifications on/off for the firm (owner/admin). |

## Chat

A private, employee-only chat channel for a firm (like Factions' `/f chat`). Requires
CarbonChat; messages route through the firm's channel once selected.

| Command | What it does |
|---|---|
| `/business chat` | Enter your firm's chat channel (joins your only firm). |
| `/business chat <firm>` | Enter a specific firm's chat when you're in several. |
| `/business chat off` | Leave firm chat. |

## Permission nodes

Business uses **two** layers of access:

1. **LuckPerms node** — gates the command itself. **Almost every `business.*` node defaults
   to everyone (`default: true`)** — including `business.use`, which the base `/business`
   command needs. The only exceptions are the staff-override nodes `business.admin.*`
   (disband / rename / attribute / proprietor / reload) and `business.tax.exempt`, which
   default to operator-only. Real authorisation for everyday commands is enforced at the
   in-firm role layer below, so the node only governs whether a player may *attempt* the
   command at all.
2. **In-firm role permission** — for actions on a specific firm, the plugin then checks
   the actor's [role permission](#roles) (`ADMIN` / `FINANCIAL`). The firm **owner
   (proprietor) bypasses this layer entirely**.

So a player needs the LuckPerms node **and** (for firm-specific actions) the right
in-firm role.

| Command(s) | Node | In-firm role |
|---|---|---|
| `/business create` | `business.create` | — |
| `/business disband` | `business.disband` | owner only |
| `/business info` | `business.info` | — |
| `/business list` · `list <player>` · `list all` | `business.list` · `business.list.other` · `business.list.all` | — |
| `/business deposit` · `balance` · `withdraw` · `pay player` · `pay business` · `send` · `transactions` | `business.finance` | `ADMIN` or `FINANCIAL` |
| `/business pay` (into a firm) | `business.pay` | — |
| `/business offer` / `hire` · `offer rescind` | `business.staff.offer` · `business.staff.offer.rescind` | `ADMIN` |
| `/business offer accept` · `reject` | `business.staff.offer.accept` · `business.staff.offer.reject` | — (the invitee) |
| `/business fire` | `business.staff.fire` | `ADMIN` |
| `/business resign` | `business.staff.resign` | — |
| `/business staff promote` · `demote` | `business.staff.promote` · `business.staff.demote` | `ADMIN` |
| `/business staff list` | `business.staff.list` | — |
| `/business staff role create` · `rename` · `delete` | `business.staff.role.create` · `.rename` · `.delete` | `ADMIN` |
| `/business staff role permission add` · `remove` · `list` | `business.staff.role.permission.add` · `.remove` · `.list` | `ADMIN` (add/remove) |
| `/business staff role list` | `business.staff.role.list` | — |
| `/business account create` · `setdefault` · `archive` | `business.account.create` · `.setdefault` · `.archive` | **owner only** |
| `/business account sync` | `business.account.sync` | — (operator) |
| `/business account addmember` · `removemember` · `addauthorizer` · `removeauthorizer` | `business.account.addmember` · `.removemember` · `.addauthorizer` · `.removeauthorizer` | **owner only** |
| `/business account list` · `members` · `authorizers` | `business.account.list` · `.members` · `.authorizers` | — |
| `/business account pay into` | `business.pay` | — |
| `/business account deposit` · `withdraw` · `pay player` · `pay business` · `balance` · `transactions` | `business.account.finance` | `ADMIN` or `FINANCIAL` |
| `/business transfer begin` · `confirm` · `cancel` | `business.transfer.begin` · `.confirm` · `.cancel` | owner only |
| `/business transfer complete` · `reject` | `business.transfer.complete` · `.reject` | — (the recipient) |
| `/business attribute set hq` · `discord` | `business.attribute.hq` · `business.attribute.discord` | `ADMIN` |
| `/business tax info` | `business.tax.info` | — |
| `/business tax exempt` | `business.tax.exempt` | operator action |
| `/business sales` · `sales summary` | `business.sales` | proprietor / `ADMIN` / `FINANCIAL` |
| `/business sales export` | `business.sales.export` | proprietor / `ADMIN` / `FINANCIAL` |
| `/business sales toggle` | `business.sales.toggle` | owner / `ADMIN` |
| `/business chat` | `business.chat` | firm member |
| `/business admin disband` | `business.admin.disband` | — (operator, bypasses firm roles) |
| `/business admin rename` | `business.admin.rename` | — (operator, bypasses firm roles) |
| `/business admin set hq` · `set discord` | `business.admin.attribute` | — (operator, bypasses firm roles) |
| `/business admin set proprietor` | `business.admin.proprietor` | — (operator, bypasses firm roles) |
| `/business reload` | `business.admin.reload` | — (operator) |

> [!NOTE]
> The base `/business` command itself needs `business.use`. In `plugin.yml` **almost every
> `business.*` node defaults to everyone (`default: true`)** — including `business.use` —
> so players can run businesses out of the box; only the staff-override nodes
> `business.admin.*` and `business.tax.exempt` default to operator-only. The "owner only"
> rows are enforced in the plugin itself — only a firm's **proprietor** can manage its
> accounts and their access, regardless of LuckPerms nodes or in-firm role. The
> `business.admin.*` overrides go the other way: they let operators act on any firm,
> ignoring proprietorship and firm roles entirely.
