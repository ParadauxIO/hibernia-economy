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

Once linked, choose **My finances** in the account menu. It is a workspace with a
section list on the left (a menu at the top on a phone):

- **Overview**: **My balance**, which is the money in your own personal
  account only, with a chart over the last 90 days (or the range you pick);
  this month's **income and spending by category** compared with last month;
  who pays you most and whom you pay most; and your latest activity.
- **Accounts you can access**, grouped by whose money it is: **Mine**, accounts
  **shared with you** by other players, each **firm** you can see (with your
  role there and the firm's own total), and **government** accounts grouped by unit (or Unassigned). Each row
  says why you can see it: owner, member, authorizer, viewer, firm finance
  (with your role) or a group. Firm and government money is never added to
  yours. A firm's money belongs to the firm, and the biggest firms are banks
  holding other players' deposits.
- **Transactions**: your full history, newest first. Filter by category,
  direction, counterparty, amount, account, memo text and date. Add an
  **Account** filter to include accounts shared with you. Category,
  counterparty and memo filters search 31 days at a time, and **Older** keeps
  searching further back. **Export** downloads every matching row as CSV or JSON.
- **Statements**: one per month, with the opening balance, money in and out by
  category, the closing balance, tax paid and the largest transactions. Print
  it (or save it as a PDF) from your browser, or download it as CSV.
- **Taxes and fines**: tax you paid by kind and month, every fine issued to you
  with its transaction, and the tax rates in force.
- **Market**: what you buy at other shops and what sells at your own.
- **Firms**: firms you own or work for, your role, its permissions and when you
  were hired. Where you have a finance role, the firm's balance and this month's
  net, labelled as the firm's money.
- **Developer**: your API keys (never their tokens) and your webhooks.

Income and spending leave out money moved between your own accounts. Money
between you and a firm, such as wages or an owner's draw, does count.

Charts and statements are built from daily summaries that update every few
minutes, shown as "Updated 14:05". Right after an Explorer release, older history
is prepared in the background, newest first. Until it is ready, those sections
say how far back they are covered.

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

When you're signed in, tables on the **Market** and **My finances** pages show **CSV** and
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
The item catalogue is ordered by trades in the last 30 days; sort by any column instead.

## Economy pages

Every economy page has a range picker (7, 30 or 90 days, a year, or your own
dates); the range is kept in the address, so a link shares the same view. Charts
use UTC days, and today is partial.

The economy pages read daily summaries that the explorer builds in the
background, so their figures are up to about five minutes behind the ledger.
Each section says when it was last updated. After a new release the summaries
are rebuilt newest day first, and a section whose range isn't ready yet says
"Preparing history: covered back to …" and totals only the days it has.

**Overview** shows personal money supply (what players hold right now), daily
active players, transactions and volume for the range, each compared with the
period of the same length before it. It also shows activity by day, a weekday ×
hour heatmap, and which plugins move the most money. Transactions and volume
count a Vault bridge transfer once: some plugins move money through a system
"bridge" account, which the ledger records as two transactions. These figures
are estimates and don't establish who paid whom. Your transaction history and
the raw API feed keep the original ledger records, so their counts can differ.
The former Health bookmark redirects here.

**Wealth** shows current concentration (Gini coefficient, top 1% and 10% shares),
money by account type, the personal balance distribution and the largest
accounts, plus their history from daily snapshots. Snapshots start on the day
the explorer began recording them; earlier days are not reconstructed. The
government total includes the accounts that create money, which hold negative
balances by design, so it can be negative. Nothing is hidden or netted off.

**Money flow** shows who pays whom by account type (personal, business,
government, system), how much each type gained or lost over the range, and the
plugins behind those changes. Transfers between someone's own accounts are left
out. The ledger is shown as it is: a payment routed through a system bridge
account appears as money into and out of System. Money flow covers up to 92 days
at a time. Comparison with a previous period is not currently available.

[Taxation](/economy/taxation) is public and shows collections, fine refunds,
net revenue by type, revenue by day and the receiving government accounts for
the range. It counts recognised personal/corporate balance tax, source income
tax, ChestShop sales tax, and recorded fines. Other government transfers and
property tax are excluded. Archived receiving accounts remain included. Refunds
count on their settlement day, so a day or period can have negative net fine
revenue. Individual payers, fine reasons and private account details are not
exposed.

The rates panel shows the latest published Treasury configuration, regardless of
the selected period. It shows enabled/disabled status, personal weekly balance-tax
brackets and per-source income-tax overrides. If live rates cannot be read, it says
unavailable; it never substitutes shipped defaults. Corporate and ChestShop rates
are not published in this panel.

## Government workspace

The [Government workspace](/gov) opens only for people with government access.
It starts with the accounts you are permitted to read. Choose a government unit
and date range to narrow the overview, revenue, spending, payroll and fines.
A **Partial** unit means you can read some of its accounts; the totals include
only those accounts. Unit membership never grants access by itself.

**Accounts** lists balances and recent activity. **Revenue** separates tax, fines
and other income; **Spending** separates wages and other expenses. Top payment
lists cover at most the last 31 days of the selected range. **Payroll** shows
payments and unusual changes for review; a flag is not proof of wrongdoing.
Its top 50 recipients follow the selected 7d, 30d, 90d, YTD or 1y range,
ranked by wages received. Historical recipients show “Preparing history” while
the worker backfills them. Run history remains limited to the last 31 days.

**Fines** supports reason search, active/revoked filters and pagination. Summary
totals cover the selected period and permitted accounts, independently of the
list filter. **Reports** provides monthly statements that can be printed or
exported as CSV. **Audit** and **Manage units** appear only with their separate
permissions.

Compliance officers with the required firm-finance and transaction permissions
can use the **Watchlist** and firm investigations. This permission is separate
from government account access. A watchlist is personal to its viewer.

If a chart says **Preparing history**, its summary data is still being built.
The page does not substitute incomplete totals for a completed historical period.

## Account pages

Every account has a page at `/accounts/<number>`, which is where links from the
game lead. Anyone can see:

- **Who holds it.** A personal account shows its owner. A business account shows
  the **firm** that holds it, and its proprietor as the firm's proprietor: a
  firm's money belongs to the firm, not to the person who runs it.
- **Its balance**, when the account was opened, and what kind of account it is.
- For **business and government accounts**, money in, money out and the number of
  transactions over the last 30 days, with a 12-month trend. Individual
  transactions stay private.
- **Related** links: the owner or firm, the firm's other accounts, and other
  accounts of the same owner.

Use **Copy link** to share the page, or **Copy /pay command** to get the in-game
command that pays this account.

If you can read the account's history (it is yours, it is shared with you, or
you run its firm's finances), you also get a balance chart, money in and out by
category, a year-long activity calendar, the top counterparties and the full
transaction list. Pick a period with the range buttons at the top. The
transaction list has the same filter chips as the directories: **Category**,
**Direction**, **Counterparty**, **Amount**, **Date** and **Message**. Use
**Older** and **Newer** to page through it; **Balance after** shows the balance
right after each transaction.

Filters look at 5,000 transactions at a time, so a busy account stays fast. If
nothing matches in that stretch, the table says how far back it looked; press
**Older** to keep looking. Searching message text without a Date filter covers
the last 30 days.

Charts and totals come from summaries that update every few minutes. While the
explorer is still building older history after an update, a section says
**Preparing history** and how far back it is covered.

## Player profiles

Each player has a profile at `/players/<name>` (any capitalisation works). It
shows when they first and last played, their personal account and balance, the
firms they run or work for with their role, and their own shops. It never shows a
firm's money or adds up a player's accounts. The **[Players](/players)**
directory lists everyone, newest activity first, with filters for first seen,
last seen and firm membership.

## Firm pages

A firm's page shows its proprietor, staff count, headquarters, its accounts and
their balances, 30 days of money in and out across those accounts (moves between
the firm's own accounts are left out) and the items its shops sell most.
**Members** lists staff and roles, for the firm's own people. **Financials**
shows revenue and costs by category, payroll, shop performance by item and a
printable monthly statement with CSV export, for the people who run the firm's
money.

## Finding items

**Market** opens with a search box, this week's price movers, the most traded
items and the newest shops. **Items** lists everything traded in the last 30 days
with its average price, trades and volume; search by name and filter or sort by
any of them. Average prices come from real trades, not shop quotes.

An item's page shows daily price candles, trades per day, the average price
shops charge and pay with the spread between them, and where to buy or sell it
now. Pick a period with the range buttons. Under **Compare**, add up to three
other items to plot their average prices together.

## Comparing shops

**Find shops** lists shop signs, cheapest first. Add **I want to: Sell** to see
shops that buy from you, best price first. Prices on signs are for a batch; the
explorer shows the price per item. **Stock** (or **Space**, when selling) shows
the last observation: **Below one batch** or **Recheck** means check before you
travel, and admin shops are unlimited. The location opens the server map.

## Search and directories on a phone

Use **Search** in the header to find accounts, firms and players. Escape closes
search and returns focus to its button. Account and firm directories keep names
and balances together on narrow screens; expand **Details** for other columns.
The players, firms, items and shops directories use the same search and filter chips
as the accounts directory below, and keep them in the page address.

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
