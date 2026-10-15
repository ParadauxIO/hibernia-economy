---
title: Using the Explorer & linking your account
order: 1.5
description: Browse the economy on the website, sign in, and link your Minecraft account to see your own data.
---

# Using the Economy Explorer

The **Economy Explorer** is the website you're reading this on. It's a live window into
the server economy — balances, firms, the ChestShop market, and how money moves around.
Most of it is open to everyone; a few things about *you specifically* unlock once you
sign in and link your Minecraft account.

## What you can browse without signing in

Plenty is public:

- **Balances and leaderboards** — who holds what, the richest accounts.
- **Firms** — companies, their accounts, and activity.
- **Market** — ChestShop prices, top items, and sales volume.
- **Money flow** and other economy-wide charts.
- **Search** — the search box up top finds accounts, players, firms, and account IDs.

## What signing in unlocks

Your **own** detailed data is private to you — your accounts, your income and spending
over time, and your personal ChestShop activity. To see it, you sign in and link your
in-game identity.

## Step 1 — Sign in

Click **Log in** in the top-right corner. You'll sign in with your account, then land
back on the Explorer. Once you're in, a link to your data appears in the top-right — it
shows your Minecraft name once you've linked (and "My data" until then).

## Step 2 — Link your Minecraft account

Signing in proves who you are on the *website*; linking connects that to your
*in-game* character so the Explorer knows which balances are yours.

1. Open **[Link your account](/link)** (or click **My data** before you're linked —
   it'll send you there).
2. Generate a short **code** on that page.
3. Join the Minecraft server and run this command within **5 minutes**:

```text
/treasuryapi ui link <code>
```

4. Come back and refresh — you're linked.

> [!NOTE]
> The code expires after 5 minutes. If it lapses, just generate a new one and run the
> command again.

## Step 3 — View your data

Once linked, open your data (top-right) to see your personal dashboard:

- Your **total balance** across all your accounts.
- Your **income and spending** over the last 90 days.
- Your top **counterparties** — who you trade with most.

From there, the **ChestShop activity** link opens a separate page breaking down what you
buy and sell, who you trade with, and a 7/30/90-day or all-time window.

> [!TIP]
> Only you can see your private data — linking ties it to your login. Public pages
> (balances, firms, market) stay visible to everyone whether you're signed in or not.

## Exporting data

When you're signed in, tables on the **Market** and **My data** pages show **CSV** and
**JSON** buttons that download what you're looking at. The buttons only appear once you're
logged in.

## Changing the look

Use the sun/moon/monitor button in the header to switch between **light**, **dark**,
and **system** (follow your device) appearance. Your choice is remembered.

## Where to go next

- New to the economy itself? Start with **[Getting started](/docs/guides/getting-started)**.
- Curious what counts as public vs. private:
  **[Accounts & money](/docs/concepts/accounts-and-money)**.

## Understanding summary figures

Open accounts means non-archived accounts, not players active recently. Personal
supply is the current player-held balance; transaction activity measures transfers
in the selected period. Balance summaries include negative net balances, while
the positive-net-balance chart excludes types whose total is zero or negative.
Market items traded counts every distinct item traded in the selected window,
not just the featured cards. Government fine totals cover all non-revoked fines
in the selected window and your permitted accounts; the recent list is limited.
The item catalogue is ordered by item key for predictable browsing.
