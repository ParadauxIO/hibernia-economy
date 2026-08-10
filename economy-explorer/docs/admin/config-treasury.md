---
title: Treasury configuration
order: 11
description: Treasury's config.yml — accounts, currency, tax cycles, balance/income tax, salaries, and database.
---

# Treasury configuration

Treasury's settings live in `plugins/Treasury/config.yml`.

> [!CAUTION]
> Most changes here are picked up by **`/treasury reload`** (permission
> `treasury.admin.reload`) — it re-reads `config.yml` and `messages.properties` and
> refreshes salaries, balance-tax brackets/rates, source-income tax, government account
> names, and the log level live. A full **server restart** is only needed for `database.*`,
> the tax/salary schedule intervals, and `economy.format` (the currency pattern). Treasury
> is the economy system of record, so a bad `database.*` value or an invalid
> `economy.format` pattern will stop the plugin from enabling.

## Currency — `economy`

| Key | Default | What it does |
|---|---|---|
| `economy.format` | `$#,##0.00` | Java `DecimalFormat` pattern for displaying money. An invalid pattern stops startup. |
| `economy.currency-name.singular` | `Redmont Dollar` | Singular currency name (used by Vault and the API). |
| `economy.currency-name.plural` | `Redmont Dollars` | Plural currency name. |
| `economy.starting-balance` | `10000` | Amount seeded into a new player's account on first join. Affects **new** accounts only. |

## Government accounts — `government`

The display names of the bootstrapped government accounts, created automatically at
startup as unlimited faucet/sink accounts (their balance can go negative by design).

| Key | Default | What it does |
|---|---|---|
| `government.starting-balances-account` | `starting-balances` | Source account every new player's starting balance is paid from. |
| `government.tax-income-account` | `DCGovernment` | The **default tax destination** — the fallback for any tax with no/missing destination. |
| `government.fines-account` | `GovernmentFines` | Account `/fine` payments are credited to (and refunded from on revoke). |

> [!CAUTION]
> **Don't rename these once the server is live.** Renaming creates a *new* empty account
> under the new name and orphans the old one (which still holds the history). A fourth
> account, `Eco`, backs `/eco give|take` and is hard-coded (not configurable).

## Tax cycles — `tax.cycles`

Treasury fires a daily/weekly/monthly **event** on schedule; it collects nothing itself —
other plugins (Realty, Business) listen and collect during the cycle. Times are in the
**server's local time zone**.

| Key | Default | What it does |
|---|---|---|
| `tax.cycles.daily.enabled` / `.hour` | `true` / `3` | Daily cycle on/off and the hour (0–23) it fires. **Realty property tax runs here.** |
| `tax.cycles.weekly.enabled` / `.hour` / `.day-of-week` | `true` / `3` / `1` | Weekly cycle; day-of-week is 1=Mon … 7=Sun. **Business balance tax runs here.** |
| `tax.cycles.monthly.enabled` / `.hour` / `.day-of-month` | `true` / `3` / `1` | Monthly cycle; day-of-month 1–28 (keep it ≤28 so the date exists in every month). |

> [!NOTE]
> A cycle with nothing registered to it fires and collects $0. If the server is down at
> the scheduled time, that run is simply missed — there's no catch-up.

## Personal balance tax — `tax.balance`

A wealth tax charged when a player logs in, prorated by time since their last login.

| Key | Default | What it does |
|---|---|---|
| `tax.balance.enabled` | `true` | Master switch. **If you delete the key it defaults to `false`** — set it explicitly. |
| `tax.balance.government-account` | `DCGovernment` | Government account that receives the tax. |
| `tax.balance.brackets` | *(see below)* | Balance-floor → weekly-rate table. |

Shipped brackets (rate is per week, applied to the **whole** balance — flat, not
marginal):

| Balance from | Weekly rate |
|---|---|
| $0 | 0% |
| $100,000 | 1.0% |
| $200,000 | 1.2% |
| $300,000 | 1.4% |
| $500,000 | 1.8% |

> [!WARNING]
> **Bracket keys must be integers — decimal keys are silently broken.** Treasury loads the
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

> [!CAUTION]
> Brackets are **flat cliffs**, not marginal — a player at exactly $300,000 pays 1.4% on
> their *entire* balance. The actual charge is `balance × rate × (days since last login /
> 7)`. Emptying the bracket map doesn't disable it (it reverts to these defaults) — use
> `enabled: false`.

## Income tax — `tax.source-income-tax`

An optional tax on money **deposited** through Vault (income), charged to the recipient.

| Key | Default | What it does |
|---|---|---|
| `tax.source-income-tax.enabled` | `false` | Master switch (off on the shipped config). |
| `tax.source-income-tax.default-rate` | `0.0` | Rate applied to any plugin not listed below (`0.08` = 8%). |
| `tax.source-income-tax.government-account` | `DCGovernment` | Where the income tax goes. |
| `tax.source-income-tax.plugin-rates` | *(empty)* | Per-plugin rate overrides, keyed by the **case-sensitive** Bukkit plugin name. |

> [!CAUTION]
> When enabled with a non-zero `default-rate`, this taxes **every** Vault deposit from
> **every** plugin. Scope it with `plugin-rates` and keep `default-rate: 0.0` unless you
> mean it. A misspelled plugin name silently falls through to the default rate.

## Salaries — `salaries`

Recurring pay to online players based on their LuckPerms group.

| Key | Default | What it does |
|---|---|---|
| `salaries.enabled` | `true` | Master switch. |
| `salaries.government-account` | `DCGovernment` | Unlimited faucet account salaries are paid from. |
| `salaries.interval` | `900` | Seconds between payout cycles (900 = 15 minutes). |
| `salaries.amount` | *(group → amount map)* | Per-LuckPerms-group salary, e.g. `senator: 65.0`. |
| `salaries.skip-afk` | `true` | Skip online-but-AFK players when paying out. |
| `salaries.afk-context-key` | `afk` | LuckPerms context key checked for AFK status. |
| `salaries.afk-context-value` | `true` | Context value that marks a player as AFK. |

Behaviour: requires **LuckPerms**; pays **online players only**; a player in several
salaried groups gets the **single highest** amount (never the sum); group names match
**case-insensitively**; groups set to `0` are intentionally unsalaried. Inherited groups
count. With `skip-afk: true` (the default), online players whose LuckPerms `afk=true`
context is set are **skipped** for that cycle; set `skip-afk: false` to pay AFK players too.

> [!CAUTION]
> If `salaries.government-account` doesn't name an existing government account, **the
> whole payroll silently does nothing** every cycle (just a log warning). This is the
> easiest salary mistake to make.

## Notifications & misc

| Key | Default | What it does |
|---|---|---|
| `tax.webhook.enabled` / `tax.webhook.url` | `false` / *(empty)* | Posts a Discord summary after each tax cycle. A blank URL disables it even when enabled. |
| `fines.webhook.enabled` / `fines.webhook.url` | `false` / *(empty)* | Posts a Discord notification when a fine is issued or revoked. A blank URL disables it even when enabled. |
| `bytebin.post-url` / `bytebin.base-url` | `pastes.paradaux.io` | Paste service the CSV transaction-export uploads to (must be a matched pair). |
| `logging.level` | `WARN` | Log verbosity: `TRACE/DEBUG/INFO/WARN/ERROR/OFF`. `INFO` shows cycles, account creation, and admin money commands. |

## Database — `database`

Connection and HikariCP pool settings (`host`, `port`, `database`, `username`,
`password`, and `pool.*`). Pool values are parsed as numbers at startup — a non-numeric
value stops the plugin enabling. Keep `pool.maximum-size` (default 30) within the
database's connection limit across all plugins. **Change credentials in production.**
