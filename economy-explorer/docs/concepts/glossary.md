---
title: Glossary
order: 3
description: Quick definitions of the economy's key terms.
---

# Glossary

Short definitions of the terms used across these docs and in-game.

## Money & accounts

- **Balance** — how much money you (or an account) currently hold. Check yours with
  `/bal`.
- **Account** — where money lives. Every transfer moves money from one account to
  another. See [Accounts & money](/docs/concepts/accounts-and-money).
- **Personal account** — your own money; nobody else can spend from it.
- **Business account** — money owned by a [firm](#firms), shared by its staff.
- **Government account** — a shared account run by a government department.
- **System account** — an internal account used by the server and plugins (e.g. the
  Vault bridge, starting-balance and tax-collection accounts). Not something players
  create or see in normal play.
- **Transaction** — a single recorded movement of money. Your history is
  `/transactions`.
- **Ledger** — the complete record of every transaction; what this Explorer reads.

## Access

A shared account has three access tiers: **Viewer** < **Member** < **Authorizer**.

- **Viewer** — read-only access to a shared account: can see its balance and transactions
  but cannot spend or manage it. (Used on government accounts.)
- **Member** — can view the account; on a **government** account, members can also spend.
- **Authorizer** — can spend from the account; on a **government** account, authorizers
  can also manage who has access (on a firm account the owner manages access). See
  [Permissions & roles](/docs/concepts/permissions-and-roles).

> **Two "viewers".** This per-account *Viewer* tier is separate from the site-wide
> Explorer `viewer` role (financial oversight across the whole economy) on
> [Permissions & roles](/docs/concepts/permissions-and-roles).

## Firms

- **Firm** (also *company* / *business*) — a player-owned business that can hold money,
  employ players, and own shops. See [Setting up a business](/docs/guides/setting-up-a-business).
- **Proprietor** — a firm's owner; can do anything in the firm, including disband it.
- **Employee / staff** — a player who works for a firm, holding one role.
- **Role** — a named set of permissions within a firm (the defaults are Proprietor,
  Co-Proprietor, Manager, Supervisor, Employee).
- **Permission** — what a role can do: `ADMIN` (manage the firm), `FINANCIAL` (move
  money), `CHESTSHOP` (run firm shops), `DEFAULT` (basic membership).

## Shops

- **ChestShop** — a chest with a sign that buys or sells items automatically.
- **Shop code** — a firm account's address (like `B:16`) used on a sign's owner line so
  the shop pays the firm. From `/business account list`.
- **Admin shop** — a shop with unlimited stock/funds, run by staff.

## Charges & pay

- **Balance tax** — a small weekly charge based on how much you hold, applied at login.
- **Income tax** — tax taken from certain income at the moment you earn it.
- **Salary** — automatic pay from the government for holding an official role, paid
  while you're online. See [Government salaries](/docs/guides/salaries).
- **Fine** — money charged to a player by the government.

## Property (Realty)

- **Freehold** — a plot you own permanently; can be sold or auctioned.
- **Leasehold** — a plot you rent for a fixed time; expires unless extended.
- **Title holder** — the owner of a freehold.
- **Landlord / tenant** — the owner and the renter of a leasehold.
- **Authority** — someone allowed to manage a plot (without owning it).
- **Agent** — a co-manager invited to help run a freehold.
- **Offer** — a price you propose to a plot's owner.
- **Auction** — a competitive, timed sale of a plot to the highest bidder.

## Developers

- **API key / token** — a secret you issue in-game (`/treasuryapi`) to let an app act
  on one of your accounts. See [Using the economy API](/docs/guides/api).
- **Linking** — connecting your website login to your in-game character so the Explorer
  can show your data. See [Using the Explorer](/docs/guides/using-the-explorer).
