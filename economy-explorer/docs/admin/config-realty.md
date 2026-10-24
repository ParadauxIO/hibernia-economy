---
title: Realty configuration
order: 14
description: The Realty property-tax formula and exemption, plus leases, signs, and tags. Config lives in plugins/Realty/.
---

# Realty configuration

Realty's config files live in `plugins/Realty/`. **`/realty reload` applies most changes live** — it re-reads every file below and swaps them in
atomically; `taxes.yml` changes take effect on the **next tax cycle** with no restart.
(The database file still needs a restart.)

Files: `taxes.yml`, `settings.yml`, `region-tags.yml`, `profiles.yml`, `messages.yml`,
`database.yml`.

---

# Property tax

Realty charges freehold owners, including offline players, on Treasury's daily
cycle. This describes the Realty revision pinned by this repository, not a
promise about a particular server's deployed configuration. Check `taxes.yml`
and the deployed plugin version before changing tax policy.

## `taxes.yml` settings

| Key | Shipped default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch; use `false` to stop property-tax collection. |
| `government-account` | `DCGovernment` | Destination; a missing account falls back to Treasury's default with a warning. |
| `exempt-uuids` | `[]` | Owners exempt from all property tax. |
| `rules` | One catch-all rule below | Ordered rules; the first match owns each plot. |
| `rules[].match.all` / `.any` | omitted | All listed tags / at least one listed tag must match; when both exist, both conditions apply. Tag IDs are case-insensitive. |
| `rules[].formula` | See below | Total daily charge for an owner's plots assigned to this rule. |
| `rules[].exempt-threshold` | `0` when omitted; `2` in the shipped rule | Owners with this many plots or fewer **in this rule** pay nothing for that rule. |

The former top-level `default-formula` and `exempt-plot-threshold` are no longer
read. There is no separate federal tax outside the rules. A plot matching no
rule is untaxed. Missing/empty rules are refilled from shipped defaults on
startup; emptying the list is not the off switch.

## The formula

The shipped catch-all rule is:

```yaml
enabled: true
government-account: DCGovernment
exempt-uuids: []
rules:
  - formula: "2.7 * (<plots> - 2) + 2.87 * (<plots> - 2)^2 + 0.0462 * (<plots> - 2)^3"
    exempt-threshold: 2
```

`<plots>` counts only that owner's plots assigned to the rule. Each formula runs
once per owner/rule; positive charges are summed and the total rounded down to
cents. Zero/negative results do not credit money. Supported operations include
`+`, `-`, `*`, `/`, `^`, parentheses and decimals; multiplication must be explicit.

## What it actually costs (worked example)

These are **shipped-default examples**, not live rates:

| Plots in the catch-all rule | Daily tax |
|---:|---:|
| 1–2 | $0.00 |
| 3 | $5.61 |
| 5 | $35.17 |
| 10 | $228.93 |
| 20 | $1,247.91 |

## Local-government overrides (`rules`)

Put narrow rules before broad ones. For example, replacing the rules with this
list exempts residential plots and charges $12 per commercial/industrial plot
when an owner has more than two plots in that category:

```yaml
rules:
  - match:
      any: [residential]
    formula: "0"
  - match:
      any: [commercial, industrial]
    formula: "12 * <plots>"
    exempt-threshold: 2
```

A plot with both residential and commercial tags goes into the first rule only.
Other plots are untaxed in this example. Above the threshold the formula applies
to the whole matched count; it is not an allowance subtracted from that count.
An invalid formula is dropped with a warning and its plots can fall through to a
later matching rule. A catch-all placed first prevents later rules from matching.

## How it's charged

Treasury transfers the charge from the owner's personal account (or first
available account when no personal account exists). Owners with no Treasury
account are skipped with a warning. Treasury enforces funding/overdraft rules;
Realty logs collected, skipped and failed results and does not evict for tax debt.
Deduplication covers the same owner and **same cycle timestamp**. Manual
`/tax trigger daily` creates a new timestamp and can charge again.

## Tuning the tax

1. Back up `taxes.yml`; record the current rule order, tags, UUID exemptions and
   destination account.
2. Calculate representative owners' charges per rule before editing, including
   owners on either side of each threshold and plots matching several tags.
3. Run `/realty reload`; inspect parse warnings and `/tax status` for Realty's
   daily integration. If validation fails, restore the saved file and reload.
4. Verify the next scheduled cycle's Treasury transactions and Realty summary.
   Rehearse manual triggers only on disposable staging accounts. Reloading the
   old settings changes future charges; it does not refund charges already made.

---

# Other settings

## `settings.yml`

| Key | Default | What it does |
|---|---|---|
| `default-freehold-authority` | `{ name: GovSecurity, type: government }` | Default authority party; must resolve. |
| `default-leasehold-landlord` | `{ name: GovSecurity, type: government }` | Default landlord party; must resolve. |
| `default-freehold-titleholder` | *(unset)* | Optional player party, e.g. `{ name: Steve }`. |
| `date-format` | `EEE, d MMMM yyyy (HH:mm)` | Date format used in messages and on signs. |
| `offer-payment-duration-seconds` | `86400` (24h) | How long a buyer has to pay after an offer is accepted before it expires and refunds. |
| `subregion-min-volume` | `20` | Minimum block volume for a subregion selection. |
| `subregion-tag-blacklist` | *(empty)* | Regions with any of these tags can't be used as a subregion parent. |
| `profile-reapply-per-tick` | `10` | How many region profiles are re-applied per tick on startup/reload (lower = less lag). |
| `account-managers` | `members` | Direct Treasury members/authorizers act for account parties; `authorizers` restricts this. Handing on a role always requires an authorizer. |
| `lease-termination-notice-seconds` | `604800` | Seven-day minimum termination notice. |
| `subregion-wand-material` | `GOLDEN_AXE` | Footprint selection tool. |
| `teleportation-starting-height` | `80` | Starting height for safe-location search in tall plots. |
| `schematic-capture-cooldown-seconds` | `3600` | Per-region preview capture cooldown; `0` disables. |
| `schematic-max-volume` | `1000000` | Capture volume limit in blocks; `0` disables, `--force` does not bypass. |
| `schematic-capture-blocks-per-tick` | `20000` | Capture work budget per tick. |

> [!NOTE]
> Lease **term, price, and max-extensions** are set per-plot with commands (`/realty
> create leasehold …`, `/realty set duration`, etc.), not in config. The expiry sweep runs
> every minute and is not configurable. Expired leases return the plot to the landlord and
> notify both parties; ending a lease early (`/realty unrent`) gives a prorated refund.

## Region tags (`region-tags.yml`)

Defines the tag vocabulary used for searching and for tax `rules`. Each tag has a display
name and a permission node controlling who can apply it. Ships with three (OP-only by
default):

| Tag | Permission |
|---|---|
| `residential` | `realty.tag.residential` |
| `commercial` | `realty.tag.commercial` |
| `industrial` | `realty.tag.industrial` |

## Signs (`profiles.yml`)

Realty signs aren't a standalone config — they're defined inside **region flag profiles**
in `profiles.yml`, layered by plot state (`FOR_SALE`, `SOLD`, `FOR_LEASE`, `LEASED`). A
profile can set WorldGuard flags/priority and a `sign:` block with up to four MiniMessage
`lines:` (placeholders like `<region>`, `<price>`, `<duration>`, `<time_left>`) plus
right/left-click commands. **`profiles.yml` ships fully commented out** — no profiles are
active until you add them.

## Database (`database.yml`)

`url`, `username`, `password` for the MariaDB connection. Empty `url` disables DB-backed
features. Database changes need a **restart** (not just `/realty reload`).

## Default-party and module upgrades

The old `default-*-uuid` settings are no longer read. Set the party objects above
for your tenant; unresolved defaults cause create/register commands to refuse
unless an explicit valid party is supplied. Supported types are `government`,
`business`, `system`, `group`, or omitted for a player; business/system config
names use `#<id>`, and players may use `uuid` instead of `name`.

See [Realty operations](/docs/admin/realty-operations) for the schema-19 upgrade,
legacy government-party reconciliation, lease permission changes, modules and
preview service checks. Realty owns its own schema migration; economy Flyway
does not upgrade its separate database.
