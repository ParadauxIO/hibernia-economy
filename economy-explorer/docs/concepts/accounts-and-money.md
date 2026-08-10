---
title: Accounts & money
order: 1
description: The kinds of accounts you'll meet — personal, business, and government (plus internal system accounts) — and how money moves.
---

# Accounts and money

Every bit of money on the server lives in an **account**, and every payment is just a
move from one account to another. You'll come across three kinds in normal play
(plus an internal fourth, covered at the end).

## Personal accounts

Your own money. This is the balance `/bal` shows, and the one `/pay` sends from. You
always have exactly one, and it's just yours — nobody else can spend from it.

## Business accounts

Money owned by a **[firm](/docs/guides/setting-up-a-business)**, not a person. A firm
can have several accounts — for example separate pots for wages, supplies, and profit.
Two kinds of access decide who can do what:

- **Members** can *see* the account and its history.
- **Authorizers** can also *spend* from it.

A firm account can also be the owner of a
**[ChestShop](/docs/guides/chestshop-business)**, so shop sales pay the company
directly.

## Government accounts

Shared accounts that government **departments** run together. **Viewers** get read-only
access — they see the balance and history but can't spend; **members** can view and
spend from them; **authorizers** can do that *and* manage who else has access. They
belong to a department rather than a company. See
**[Government accounts](/docs/guides/government-accounts)**.

## System accounts

There's also a fourth, behind-the-scenes type: **system** accounts. You won't create or
see these in normal play — they're used internally by the server and plugins (for
example, the bridge that lets other plugins move money, and the accounts that seed
starting balances or hold collected tax). They're listed here only for completeness.

## How money moves

A payment is always a transfer: the amount leaves one account and arrives in another at
the same moment. There's no separate "withdraw cash" step — money goes straight from
sender to recipient.

Because of that, **every transfer is recorded**. You can review your own with
`/transactions`, a firm's with `/business transactions`, and a government account's with
`/government account history`. The same records are what power the
[Economy Explorer](/) — the balances, market prices, and money-flow views all come from
this shared history.

> [!NOTE]
> Money is only ever created or destroyed by server operators (via admin commands) or
> by built-in things like the starting balance you get when you join and the tax
> cycles. Everything else is players moving existing money around.

## Where to go next

- **[Permissions & roles](/docs/concepts/permissions-and-roles)** — who's allowed to
  spend.
- **[Getting started](/docs/guides/getting-started)** — the everyday money commands.
