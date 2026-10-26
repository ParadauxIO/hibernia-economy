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
- **Firms** — public company profiles and directory balances; internal accounts and activity require permission.
- **Market** — ChestShop prices, top items, and sales volume.
- **Money flow** and other economy-wide charts.
- **Search** — press **Ctrl K** (**⌘K** on a Mac), **/**, or the **Search** button
  in the top bar. Type two or more letters to find players, accounts (by name or
  ID) and firms, or the name of a page ("statements", "taxation") to jump there.
  Use the arrow keys and **Enter**, or click. Things you open from search are
  listed under **Recent** next time; they are kept only in your browser. The
  same box can copy the server address and switch between light and dark.
  Suggestions match the start of a name. For a fuller search, choose **Search
  everything**: the [search page](/search) also finds account, firm and item names
  that contain your text anywhere, groups the results with a count for each
  type, and has a tab per type. Player names always match from the first letter.
  Type an account number (`#1204`) or a player's full UUID to go straight to it.

## Finding your way around

The top bar has the main sections: **Economy**, **Directory** (players,
accounts and firms), **Market**, and **Docs**. **Government** and
**Investigate** appear only for people whose role and accounts give them
access. The pages of the section you are in are listed just below the top bar.
On a phone, the **Menu** button at the top right lists every section and page.

## Dates and times

Timestamps use your browser’s local timezone, including daylight saving time.
The timezone is shown beside each timestamp; hover over it for the exact UTC
instant and full timezone name. Before JavaScript loads (or if it is disabled),
timestamps are shown in UTC.

Daily charts and activity calendars use **UTC days**, as labelled. Their totals
cover midnight to midnight UTC, so a local timestamp can fall on a different
calendar date from its chart bucket.

## What signing in unlocks

Signing in and linking your in-game identity unlocks your detailed account,
income/spending and personal ChestShop data. Shared-account users and authorized
staff may also read records according to their permissions.

## Step 1 — Sign in

Click **Log in** in the top-right corner. You'll sign in with your account, then land
back on the Explorer. Once you're in, your account menu appears in the top-right. After
you link your Minecraft account it shows your head, your name and your **personal
balance**: the money in your own personal account. Money in firms you own or work for,
and in government accounts you can see, is never added to it, because that money is not
yours personally.

Open the menu for your own pages: **My finances**, **My transactions**,
**Statements**, **My market**, **My firms**, **Webhooks and API**, and **Link
status**. **Log out** signs you out of the Explorer and of your server account
login; you may be asked to confirm.

## Step 2 — Link your Minecraft account

Signing in proves who you are on the *website*; linking connects that to your
*in-game* character so the Explorer knows which balances are yours.

1. Open **[Link your account](/link)** (before you're linked, the account menu
   starts with **Link your Minecraft account**).
2. Generate a short **code** on that page.
3. Join the Minecraft server and run this command within **5 minutes**:

```text
/treasuryapi ui link <code>
```

4. Come back and refresh — you're linked.

Bedrock/Floodgate players use this same in-game linking flow. Run the command
while signed in as the Minecraft player whose records you want to access; the
link uses that player's UUID, including Floodgate identities.

> [!NOTE]
> The code expires after 5 minutes. If it lapses, just generate a new one and run the
> command again.

## Step 3 — View your data

Once linked, choose **My finances** in the account menu to see your personal dashboard:

- Your **total balance** across all your accounts.
- Your **income and spending** over the last 90 days.
- Your top **counterparties** — who you trade with most.

From there, the **ChestShop activity** link opens a separate page breaking down what you
buy and sell, who you trade with, and a 7/30/90-day or all-time window.

> [!TIP]
> Linking identifies your own data. Authorized investigators and administrators
> can also read private data according to their configured permissions. Shared
> account relationships grant access to those accounts. Public balances, firm
> profiles and aggregate market pages remain public.

## Opening a transaction

You can open a transaction involving an account you have permission to read,
including shared government accounts. Its detail page shows every posting and
counterparty for that transaction, so you can trace the payment. This does not
expose the counterparty's other transactions or private account history. The
whole-economy transaction list still requires staff investigation permission.

## Exporting data

When you're signed in, tables on the **Market** and **My data** pages show **CSV** and
**JSON** buttons that download what you're looking at. The buttons only appear once you're
logged in.

Tables with an **Export** menu (the accounts directory, and the transaction and
sales lists for staff) can also download **all results**: every row your current
search and filters select, not just the page on screen. A download stops at
100,000 rows; the last line of the file says so when that happens, so narrow
the filters to get the rest. You can start 10 exports a minute, and each export
is recorded in the access log.

## Changing the look

Use the sun/moon/monitor button in the top bar (in the **Menu** on a phone) to switch between **light**, **dark**,
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
in the selected window and your permitted accounts; the fines list is paginated.
The item catalogue is ordered by item key for predictable browsing.

## Economy pages

Overview combines activity and player participation for 7 or 30 days. Transaction
and volume comparisons use the preceding period of equal length; there is no
percentage when that period had no activity. The former Health bookmark redirects
here. Wealth contains current distribution, inequality and account rankings.
Money Flow shows received, sent and net received amounts for each account type.
Only attributable transfers between different types are counted, including initial
player funding. Daily chart buckets use UTC; the first and current day can be partial.

Volume, transaction counts and Money Flow estimate matching adjacent Vault bridge
legs as one economic event. These figures are labelled estimates: they do not
establish who paid whom. Unmatched reward faucets and money sinks remain included.
Your transaction history and raw API feed retain the original ledger records,
so their counts can differ from these aggregates.

[Taxation](/economy/taxation) is public and shows 7- or 30-day collections,
fine refunds, net revenue by type, and receiving government accounts. It counts
recognised personal/corporate balance tax, source income tax, ChestShop sales tax,
and recorded fines from the ledger. Other government transfers and property tax
are excluded. Archived receiving accounts remain included. Refunds count on their
settlement date, so a period can have negative net fine revenue. Individual payers,
fine reasons and private account details are not exposed.

The rates panel shows the latest published Treasury configuration, regardless of
the selected period. It shows enabled/disabled status, personal weekly balance-tax
brackets and per-source income-tax overrides. If live rates cannot be read, it says
unavailable; it never substitutes shipped defaults. Corporate and ChestShop rates
are not published in this panel.

The Government tab appears only if your role includes government access.
Government opens with accounts you may view. Its Fines tab supports reason search,
active/revoked filtering and pagination. Summary totals always cover the whole
selected period for your permitted accounts, independent of the list filter.

## Finding items

Market starts with a search across the full catalogue and popular items for 7 or
30 days. Browse Items includes the complete alphabetical catalogue; Find Shops
lists observed shops; Activity contains market-wide totals and charts. Item cards
show historical daily average prices per item, not guaranteed shop offers.

## Comparing shops

Choose **I want to buy** to buy from shops at the lowest unit price, or **I want to
sell** to sell your items to the highest bidder. Prices on signs are for a batch;
the Explorer divides by that batch quantity for a fair per-item comparison. The
optional 64-item view is an estimate, not a promise that the item stacks to 64.
Check the sign batch, stock observation time and shop observation time before
travelling. Copy location copies the world and coordinates; the map link opens
the server map. Offers show both observed stock and space available: the number
of additional items of that type the container can accept. Zero space means the
container is full for that item. Admin shops show unlimited stock and space;
unknown observations stay unknown. Stock and space share the observation time
and may have changed since the last check.

Item pages show observed offers before historical averages. The simple daily
price chart is followed by an optional advanced candlestick view. Historical
sellers do not necessarily still have an active shop. Private sales figures retain
their existing permission requirements.

## Search and directories on a phone

Use **Search** in the header to find accounts, firms and players. Escape closes
search and returns focus to its button. Account and firm directories keep names
and balances together on narrow screens; expand **Details** for other columns.
The firms directory has sort presets (Richest, Newest, Name A–Z, Largest employers).
The selected sort is shown above the table and stays in the URL with your filters.

## Filter the accounts directory

The **[Accounts](/accounts)** directory is sorted by balance, highest first.
Click a column heading to sort by it, and click it again to reverse the order.

- **Search** matches account names and owner names. Type a full player UUID to
  find that player's accounts, or `#123` to jump to account number 123.
- **+ Filter** adds a filter on type, balance, created date, firm or archived
  state. Each filter shows as a chip under the search box: click a chip to change
  it, or **×** to remove it. **Clear all** removes every filter.
- You can also type filters straight into the search box, and they turn into
  chips when you press space or Enter:

  | Type this | To get |
  |---|---|
  | `type:business` | Business accounts |
  | `type:business,government` | Business or government accounts |
  | `balance:>10k` | Balances over 10,000 (`<`, `>=`, `<=` work too) |
  | `balance:1k..50k` | Balances from 1,000 to 50,000 |
  | `created:2026` or `created:2026-03` | Opened in that year or month |
  | `archived:yes` | Archived accounts, which are otherwise hidden |

- Backspace in an empty search box removes the last chip.
- **Columns** hides the Type and Created columns. The explorer remembers your
  choice on this device.
- Filters are part of the page address, so you can bookmark or share a filtered
  view.
Account details show a single current balance; owner UUIDs are expandable.
Authorised viewers can follow a business account back to its firm.

## Recover your website password

If you already linked your Minecraft account, the server can let you reset the
website password without email:

```text
/treasuryapi ui resetpassword
/treasuryapi ui resetpassword confirm
```

Run both yourself in-game. Copy the temporary password from the private response,
sign in with your existing website username, then choose a permanent password
when prompted. The temporary password must be replaced at login; it has no
separate timed expiry. Keep it private, including your client chat log.

This requires server support and an existing, unambiguous link. Missing/multiple
links or a disabled integration need staff assistance; you cannot select another
person's username. Confirmed attempts are limited to one per minute. Resetting
the password does not explicitly sign out existing sessions or revoke API keys;
report a suspected compromise to staff as well.
