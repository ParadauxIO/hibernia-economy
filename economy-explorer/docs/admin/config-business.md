---
title: Business configuration
order: 12
description: Business's config.yml — the firm ownership limit and the weekly firm balance tax.
---

# Business configuration

The Business (firms) plugin's settings live in `plugins/Business/config.yml`. It's a small
file — four sections: `database`, `firm`, `sales`, and `tax`.

> [!CAUTION]
> **`/business reload` re-reads `config.yml` at runtime** — the firm ownership limit, the
> creation cooldown, name blocklist, and the balance-tax brackets all refresh without a restart. The
> exception is the **database connection** (and the listeners / scheduled jobs wired up at
> startup): those are fixed when the plugin loads, so changing the `database` block needs a
> **server restart**.

## PlaceholderAPI — firm balance

Install PlaceholderAPI alongside Business and restart the server to use
`%business_firm_balance_<name>%` in PlaceholderAPI-enabled displays. The expansion
ships with Business; no separate eCloud download or configuration key is needed.

For example, `%business_firm_balance_Acme%` returns Acme's total balance across all
live Treasury accounts as a plain decimal, such as `1234.56`, without a currency
symbol or thousands separators. Use the full firm name, preserving spaces and
underscores: `%business_firm_balance_Acme_Holdings%` looks up `Acme_Holdings`.

The placeholder works without a player context. It exposes the named firm's balance
to anyone who can render that placeholder; it does not check firm membership.
Unknown, incomplete, missing-firm and archived-firm placeholders remain unresolved.
An existing firm with no live accounts returns zero.

Balances are read on each request, so use a reasonable refresh interval for displays
that render many firms. The expansion survives `/papi reload` and unregisters when
Business disables. Without PlaceholderAPI, Business starts normally.

## Firm ownership — `firm`

| Key | Default | What it does |
|---|---|---|
| `firm.owned-limit` | `3` | Maximum firms a single player may **own** (be proprietor of). `0` or less = unlimited. |
| `firm.create-cooldown-seconds` | `300` | Minimum seconds between a player creating firms (counts **disbanded** firms too, so rapid create/disband cycles still wait). `0` or less = disabled. |

Notes:

- Counts only firms you're the **proprietor** of, not firms you're employed at.
- Enforced **only at creation** — a player can end up over the limit by receiving a firm
  transfer, but can't `create` a new one while at or over it.

## Firm name filtering — `firm.name-filter`

Set `firm.name-filter.blocked-terms` to your server's prohibited words or phrases,
then run `/business reload`. The default is `[]` (no word filtering).

```yaml
firm:
  name-filter:
    blocked-terms:
      - "blockedword"
      - "blocked phrase"
```

Business rejects matching names before creating a firm or renaming one, including
administrator renames. Matching ignores case, spaces, underscores, hyphens and
periods: `Blocked.Word Trading` matches `blockedword`. Terms match anywhere inside
the name, so choose short terms carefully to avoid rejecting innocent names.
Entries are literal text, not regular expressions; empty or separator-only entries
are ignored. This is a configurable word filter, not a complete profanity dictionary
or a leetspeak detector.

Existing names remain unchanged. This setting applies to the Business plugin;
the separate REST/Explorer rename service does not read the plugin's configuration.
No ChatSentry installation is required. ChatSentry's documented API currently has
no arbitrary-text checking method, so Business uses this local filter instead.

## Firm sales export & notifications — `sales`

Controls the firm ChestShop sales export (`/business sales export`) and real-time sale
notifications (`/business sales toggle`).

| Key | Default | What it does |
|---|---|---|
| `sales.explorer-url` | `""` | Base URL of the Economy Explorer used to build sales-report links. Blank disables `/business sales export` (it reports the feature as unavailable). |
| `sales.max-export-days` | `30` | Maximum number of days a sales export may cover. `0` or less = no cap. |
| `sales.notify-default` | `false` | Whether firms have real-time sale notifications on by default. |
| `sales.notify-flush-seconds` | `15` | How often (seconds) batched sale notifications are flushed to firm staff. |

## Firm balance tax — `tax.balance`

A weekly wealth tax on each firm's total balance, charged on Treasury's **weekly** tax
cycle.

| Key | Default | What it does |
|---|---|---|
| `tax.balance.enabled` | `true` | Master switch. **Deleting the key defaults it to `false`** — set it explicitly. |
| `tax.balance.government-account` | `DCGovernment` | Government account the tax is paid into (falls back to the literal account name `DCGovernment` if missing or blank). |
| `tax.balance.brackets` | *(see below)* | Balance-floor → weekly-rate table. |

Shipped brackets (flat, applied to the firm's **whole** balance — not marginal):

| Firm balance from | Weekly rate |
|---|---|
| $0 | 0% |
| $100,000 | 1.0% |
| $200,000 | 1.2% |
| $300,000 | 1.4% |
| $500,000 | 1.8% |

> [!WARNING]
> **Bracket keys must be integers — decimal keys are silently broken.** Business loads the
> brackets via `getConfigurationSection("tax.balance.brackets").getKeys(false)` and parses
> each key as the balance floor. Bukkit/SnakeYAML treats a `.` in a YAML mapping key as a
> **path separator**, so a decimal key like `100000.00:` is read as a nested section
> (`100000` → `00`) rather than a scalar key — its rate **never applies**. Always use whole
> integer threshold keys; the value is the **weekly rate** at that balance floor:
>
> ```yaml
> tax:
>   balance:
>     brackets:
>       0: 0.0
>       100000: 0.01
>       200000: 0.012
>       300000: 0.014
>       500000: 0.018
> ```

How it's charged: the tax is `total firm balance × rate`, split proportionally across the
firm's positive-balance accounts in whole cents. Shares are rounded down first;
remaining cents go to the largest fractional remainders (ties use larger balances,
then account order). Deduplication protects replay of the **same period**, not a
new manual `/tax trigger weekly`, which supplies a new timestamp and can charge again. A firm whose total balance is zero or negative is
skipped.

> [!NOTE]
> Emptying the bracket map doesn't disable the tax — it silently reverts to these
> defaults. Use `enabled: false` to turn it off. Individual firms can be exempted in-game
> with `/business tax exempt <firm> true` (this is a per-firm flag stored in the database,
> not a config key); check a firm's status with `/business tax info <firm>`.

## Database — `database`

`host`, `port`, `database`, `username`, `password`. Business **shares Treasury's
database** (default schema `treasury`) — don't point it elsewhere or firm/account links
break. The connection pool and `table-prefix` are not meaningfully tunable
(`table-prefix` is read but unused). Database changes need a restart.

## Not configurable

There's **no firm-creation fee, account-creation fee, or configurable role/permission
defaults** — those behaviours are fixed in code. The only tax is the weekly balance tax
above.

## Automatic account-access recovery

Business checks linked accounts at startup and approximately once per minute to
repair incomplete membership updates after a Treasury/database failure. Access is
recomputed from the current proprietor and staff roles. Failed accounts are logged
with their firm and account IDs and retried on the next pass; restarting the server
also resumes recovery. No configuration change is needed.

Until a pass succeeds, an account may still have stale access. If warnings repeat,
restore database/Treasury availability and check that the next pass succeeds.
