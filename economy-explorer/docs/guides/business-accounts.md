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

### Paying into a firm

`/firm deposit <firm> <amount> [memo]` is an alias for
`/firm pay into <firm> <amount> [memo]`. Anyone with the normal payment
permission can pay from their personal balance into a firm's default account;
you do not need to work for that firm. Tab completion suggests only firms you
own or work for. Type another firm's name manually to pay it. An optional memo
is recorded in the ledger. Withdrawals and account-specific deposits retain
their existing finance permissions.

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

Account display names can repeat, including across firms. Choose a descriptive
label such as `Wages`, then use the account's numeric ID to select the right wallet.
Labels do not reserve names across players, firms or shop aliases. Shop aliases
have separate rules: clearing an old alias keeps it reserved to the same account
so old signs cannot be redirected to someone else's wallet.

## Move money to and from a specific account

```text
/business account deposit Acme <accountId> 2000
/business account withdraw Acme <accountId> 500
```

## Move money between your firm’s accounts

Use the account IDs from `/business account list <firm>`:

```text
/business account transfer Acme 42 51 1000
/business account transfer Acme 42 51 1000 July reserve funding
```

This transfers directly from account #42 to #51 without passing through your personal
balance. Both accounts must be live and linked to Acme, including any subaccounts.
You need the same finance permission and source-account spending authority as for a
withdrawal. The optional memo is saved in the transaction history. Transfers to the
same account, to another firm's account, or for more than the source balance are rejected.

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
> **ADMIN** permission (or being the owner). See below for how that reaches each
> account.

## Who can see and spend

Access to an account comes from firm roles, and it is the same on every account the
firm has:

- Staff whose role has **`FINANCIAL`** become **members**: they can see the account
  and its history, and spend from it.
- Staff whose role has **`ADMIN`** become members and **authorizers**. Being an
  authorizer only adds something on an account set to require authorization, which
  firm accounts aren't unless server staff change it.
- Everyone else, including `CHESTSHOP` and `DEFAULT` staff, has no access.

This is kept in sync automatically whenever someone is hired, promoted, demoted or
fired, or a role's permissions change. There is no way to add or remove someone on a
single account by hand: to change what a person can do, change their role (or the
permissions on that role). You can check who currently has access:

```text
/business account members Acme <accountId>
/business account authorizers Acme <accountId>
```

> [!TIP]
> Anyone with `FINANCIAL` or `ADMIN` can spend from all of the firm's accounts, so
> give those permissions only to roles you trust with the firm's money.

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
