---
title: Setting up a business
order: 2
description: Register a firm, hire and organise staff, fund its accounts, and pay people — all in-game.
---

# Setting up a business

A **firm** is your company on the server. Once you create one it gets its own bank
account, can employ other players, and can own ChestShops. This guide takes you from
nothing to a running business with staff and money.

Everything below uses the `/business` command. It also answers to `/firm` and
`/company` if you prefer.

> [!NOTE]
> You can own up to **3 firms** by default. Pick a **single-word** name of letters and
> digits (you can also use `_`, `.`, or `-`) — e.g. `Acme`, `RedStone`, `North_Mining`.
> Spaces and other symbols won't work; they're stripped automatically. The name is 2–32
> characters and **can't start with a digit**.

## Choose an available name

Firm names must be available among active firms and disbanded firms whose names
have not been released. Disbanding keeps the name reserved until an administrator
explicitly releases it. The server can also reject names through its configured
blocked-name list.

There is no shared reservation namespace across firms, players, account labels
and shop aliases. Account labels can repeat; use account IDs to distinguish them.
Shop aliases have their own uniqueness and permanent reservation rules, described
in [ChestShops for your firm](/docs/guides/chestshop-business#shop-aliases).

## Create your firm

```text
/business create Acme
```

That's it — you're now the **proprietor** (owner) of Acme. Creating the firm also:

- sets up five starter **roles** for your staff (Proprietor, Co-Proprietor, Manager,
  Supervisor, Employee), and
- opens a **corporate bank account** for the firm.

Check it any time with:

```text
/business info Acme
```

## Put money in the firm

Your firm starts with an empty account. Move some of your own money into it:

```text
/business deposit Acme 5000
```

See how much the firm is holding:

```text
/business balance Acme
```

> [!TIP]
> `deposit` and `balance` use the firm's **default** account. Once you have more than
> one account (see [below](#using-more-than-one-account)) you can target a specific
> one.

## Hire staff

Hiring is a two-step handshake. You send an offer; the player accepts it.

You (as owner or an ADMIN) send the offer:

```text
/business offer Acme Steve
```

Steve accepts:

```text
/business offer accept Acme
```

> [!WARNING]
> An offer **expires after 5 minutes**. If Steve doesn't accept in time, just send it
> again. You can also pull it back early with `/business offer rescind Acme Steve`.

New hires join on the lowest role, **Employee**. To see who works for you:

```text
/business staff list Acme
```

To let someone go — or to leave a firm yourself:

```text
/business fire Acme Steve     # you remove an employee
/business resign Acme         # you quit a firm you work for
```

## Roles and permissions

Every employee has a **role**, and every role grants a set of **permissions**. Your
firm starts with five roles, from most senior (rank 1) to most junior (rank 5):

| Role | Rank | Default permission | Can do |
|---|---|---|---|
| **Proprietor** | 1 | `ADMIN` | Staff/role management and authorized spending; account administration stays proprietor-only |
| **Co-Proprietor** | 2 | `ADMIN` | Staff/role management and authorized spending; account administration stays proprietor-only |
| **Manager** | 3 | `FINANCIAL` | Read finances; spending also needs account authorization |
| **Supervisor** | 4 | `CHESTSHOP` | Run the firm's ChestShops |
| **Employee** | 5 | `DEFAULT` | Basic membership — no special powers |

> [!NOTE]
> The **lower** the rank number, the more senior the role — rank 1 sits at the top.
> The firm **owner** can always do everything, regardless of role.

The four permissions a role can hold:

| Permission | What it lets the holder do |
|---|---|
| `ADMIN` | Manage staff/roles and authorized spending; not proprietor-only account controls |
| `FINANCIAL` | Move the firm's money — deposit, withdraw, and pay |
| `CHESTSHOP` | Create and use ChestShops that belong to the firm |
| `DEFAULT` | Basic membership only |

Move people up and down the ladder (promote moves toward rank 1):

```text
/business staff promote Acme Steve
/business staff demote Acme Steve
```

Want a custom role — say a "Treasurer" who can handle money but not hire people?
Create it with a rank number, then give it the `FINANCIAL` permission:

```text
/business staff role create Acme Treasurer 3
/business staff role permission add Acme Treasurer FINANCIAL
```

The number (`3`) is the role's rank, used by promote/demote. List your roles and their
permissions any time:

```text
/business staff role list Acme
```

## Pay your staff

Pay a wage straight from a firm account to a player:

```text
/business account pay player Acme <accountId> Steve 1000
```

Find the `<accountId>` with `/business account list Acme` (see next section). Paying
out requires the **FINANCIAL** or **ADMIN** permission (or being the proprietor),
and account authorization. ADMIN staff are synchronized as authorizers; FINANCIAL
staff need the proprietor to grant authorization. Account creation, default-account
selection and access management are proprietor-only.

## Using more than one account

Bigger firms often split money across accounts — wages, supplies, profit. List what
you have:

```text
/business account list Acme
```

Each account shows an **ID**, whether it's the default, and a short **shop code**
(you'll use that in the [ChestShop](#connect-a-chestshop-to-your-firm) section).

Create another account and make it the default if you like:

```text
/business account create Acme Wages
/business account setdefault Acme <accountId>
```

Move money in and out of a specific account:

```text
/business account deposit Acme <accountId> 2000
/business account withdraw Acme <accountId> 500
```

> [!NOTE]
> **Members vs. authorizers.** Anyone who can *see* an account is a **member**; anyone
> who can *take money out* is an **authorizer**. Your staff are kept in sync with the
> account automatically based on their role, but you can adjust it by hand:
> ```text
> /business account addauthorizer Acme <accountId> Steve
> ```

## Connect a ChestShop to your firm

A ChestShop normally pays the player who built it. To make a shop pay **your firm**
instead, put the firm account's **shop code** on the top line of the shop sign.

1. Run `/business account list Acme` and note the shop code for the account you want
   (it looks like `B:G` — copy it exactly; it isn't simply the account number).
2. When you build the shop sign, set the **first line** to that code:

```text
B:G
64
100:90
DIAMOND
```

Now every sale routes into that firm account instead of your personal balance. To
build firm shops, an employee needs the **CHESTSHOP** permission — the **Supervisor**
role has it by default, and the firm owner can always build firm shops.

> [!TIP]
> See [Selling with ChestShops](/docs/guides/chestshop-basics) for the complete
> sign walkthrough and [firm shop aliases](/docs/guides/chestshop-business#shop-aliases)
> for memorable `A:alias` signs.

## Hand the firm to someone else

Selling or passing on the business? Ownership transfers in a confirmed handshake:

```text
/business transfer begin Acme Steve        # you start it (gives you a code)
/business transfer confirm Acme Steve <code>   # you confirm with that code
/business transfer complete Acme Steve     # Steve accepts and becomes owner
```

## Reusing a disbanded firm's name

Disbanding a firm keeps its name reserved. An administrator can explicitly release
it with `/business admin releasename <firmId>`, using the disbanded firm's numeric
ID from `/business info <firm>`. Only then can a new firm or rename reuse the name.
Active firms cannot have their names released. Names remain case-insensitively unique
among active firms and disbanded firms whose names have not been released.

The old firm keeps its name and history; reuse does not transfer its accounts,
staff, or ownership. Name lookups prefer the active firm. Use the old firm's
numeric ID to refer to its history or resume an incomplete disband after reuse.

## What's next

- Browse every money command in the **[Treasury reference](/docs/reference/treasury)**.
- Every `/business` subcommand in one place: the
  **[Business reference](/docs/reference/business)**.
