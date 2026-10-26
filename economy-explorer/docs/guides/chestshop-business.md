---
title: ChestShops for your firm
order: 6
description: Point a chest shop at a business account so sales pay the company, and run shops across multiple accounts.
---

# ChestShops that pay your firm

By default a ChestShop pays the player who built it. If you run a
[business](/docs/guides/setting-up-a-business), you'll usually want the money to go to
the **company** instead — into one of its accounts. This guide shows how, including
splitting different shops across different accounts.

## Find your account's shop code

Every firm account has a short **shop code** that looks like `B:G` — a `B:` prefix and a
short code. List your accounts to find it:

```text
/business account list Acme
```

Each account row shows its ID and its shop code. The code isn't just the account number,
so **copy it exactly** from the list. Pick the account you want this shop to pay into and
note its code.

## Use the code as the shop owner

Build the shop exactly like a [normal one](/docs/guides/chestshop-basics), but on the
**first line** (the owner line), put the account's shop code instead of your name:

```text
B:G            ← line 1: the firm account, not a player
64
100 : 90
DIAMOND
```

The code after `B:` is a short **base-36 code** for the account — *not* the account's
decimal number — so copy it exactly from the list rather than typing the account number.

Now every sale at this shop pays into that firm account, and every purchase is funded
from it.

> [!NOTE]
> You may still see older shops using a `b:<FirmName>` form — lowercase `b:` followed by
> the firm's *name* instead of an account code. These still work (the prefix is matched
> case-insensitively and resolves to the firm's default business account), and the sign
> quietly self-heals to the modern `B:<code>` form. New shops should use the account code.

> [!NOTE]
> To build a shop that belongs to the firm, you need the **CHESTSHOP** permission in
> that firm. By default the firm owner (the **Proprietor**) and the **Supervisor** role
> can build firm shops. Permissions don't stack by seniority — having `ADMIN` or
> `FINANCIAL` does **not** include `CHESTSHOP` — so grant it to any other role explicitly
> with `/business staff role permission add Acme <role> CHESTSHOP`.

## Running shops across several accounts

Because each shop points at a specific account code, you can split your operation
however you like:

- A **Wages** account funding a shop that buys raw materials from players.
- A **Storefront** account collecting sales from your retail shops.
- A **Profit** account you sweep money into.

Create the accounts you need, then put each one's code on the relevant shops:

```text
/business account create Acme Storefront
/business account list Acme            # note the new code, e.g. B:1A
```

```text
B:1A
1
B 250
NETHERITE_INGOT
```

> [!TIP]
> Keep a sign or a note of which account code maps to which purpose — once you have a
> few shops, the codes are easier to mix up than account names.

## Where to go next

- Manage the money those shops collect: **[Business accounts](/docs/guides/business-accounts)**.
- Sign format and commands in full: the **[ChestShop reference](/docs/reference/chestshop)**.

## Shop aliases

The proprietor can give each firm account one memorable alias:

```text
/business account alias Acme 16 Acme
/business account list Acme
```

Use the **decimal account ID** from the account list in the command. Put `A:Acme`
on the first line of a shop sign. ChestShop checks your usual `CHESTSHOP` permission
and **keeps `A:Acme` visible**, both when the shop is created and after trades.
Payments still go to account 16. Numeric account-code signs continue to work.

Aliases are globally unique, case-insensitive, and contain **1–12 letters (A–Z)
only**. Digits, spaces, underscores and hyphens are not allowed. The `A:` prefix
identifies an alias; it is not part of the alias itself. The older `B:@Acme` form
no longer works: rewrite those signs as `A:Acme`. Each alias points to one
specific account, independently of the firm's default account. Archived accounts
and disbanded firms cannot be resolved by alias.

Set another alias with the same command, or clear it with:

```text
/business account clearalias Acme 16
```

Changing or clearing an alias stops signs using the old alias from working. Update
those signs to the new alias or the account code. Old aliases stay reserved to their
original account permanently, so another account cannot take over existing signs.
The proprietor can restore a previous alias to its original account. Business must
be installed to resolve aliases.
