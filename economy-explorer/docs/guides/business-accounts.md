---
title: Business accounts & money
order: 4
description: Fund your firm, split money across accounts, pay staff and other firms, and control who can spend.
---

# Business accounts and money

Your firm can hold money in one or more **accounts** — for example, separate pots for
wages, supplies, and profit. This guide covers funding them, moving money around, and
deciding who's allowed to spend. Examples use a firm called `Acme`.

## The default account

When you created your firm it got one account automatically — its **corporate
account**. The simple money commands act on whichever account is the **default**:

```text
/business deposit Acme 5000     # move your money into the firm
/business balance Acme          # what the firm is holding
/business withdraw Acme 1000    # take money back out
```

## List and create accounts

See all of the firm's accounts:

```text
/business account list Acme
```

Each row shows the account's **ID**, whether it's the default, and a short **shop
code** highlighted in light blue (you'll use that to point a ChestShop at the account — see
[ChestShops for your firm](/docs/guides/chestshop-business)).

Open another account, and switch the default if you like:

```text
/business account create Acme Wages
/business account setdefault Acme <accountId>
```

Use the account ID from `account list` wherever a command asks for `<accountId>`.

## Move money to and from a specific account

```text
/business account deposit Acme <accountId> 2000
/business account withdraw Acme <accountId> 500
```

## Pay people and other firms

Pay a wage from a firm account straight to a player:

```text
/business account pay player Acme <accountId> Steve 1000
```

Pay another business:

```text
/business account pay business Acme <accountId> RivalCorp 2500
```

> [!NOTE]
> Spending the firm's money — withdrawing or paying out — needs the **FINANCIAL** or
> **ADMIN** permission (or being the owner), **and** being an authorizer on the
> account (see below).

## Who can see vs. who can spend

Each account has two kinds of access:

- **Members** can *see* the account and its history.
- **Authorizers** can also *take money out* of it.

Your staff are kept in sync with each account automatically based on their roles:
`ADMIN`-role staff become **members and authorizers** (view *and* spend), while
`FINANCIAL`-role staff become **members only**. To let a FINANCIAL employee actually
spend, add them as an authorizer by hand. Only the firm's **owner** can change an
account's members or authorizers:

```text
/business account members Acme <accountId>
/business account addauthorizer Acme <accountId> Steve
/business account removeauthorizer Acme <accountId> Steve
```

> [!TIP]
> A good pattern: keep most staff as plain members so they can see the books, and make
> only a trusted few authorizers who can actually spend.

## Review the firm's spending

History for one account, or for the whole firm at once:

```text
/business account transactions Acme <accountId>
/business transactions Acme
```

## Where to go next

- Route shop sales into a firm account: **[ChestShops for your firm](/docs/guides/chestshop-business)**.
- The complete list: the **[Business reference](/docs/reference/business)**.
- Background on the different kinds of accounts: **[Accounts & money](/docs/concepts/accounts-and-money)**.
