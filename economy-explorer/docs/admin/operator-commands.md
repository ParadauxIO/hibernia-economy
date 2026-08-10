---
title: Operator commands
order: 3
description: Adjusting balances, running tax cycles, and where salaries are configured.
---

# Operator commands

The money and economy controls available to operators. All of these are logged.

## Adjusting balances — `/eco`

Directly change a player's balance. This creates or destroys money outside the normal
economy, so use it deliberately.

```text
/eco give Steve 1000        # add money
/eco take Steve 250         # remove money
/eco set Steve 0            # set to an exact amount
/eco reset Steve            # back to the starting balance
/eco give DCGovernment 1e6  # top up a government account
```

| Argument | What it is |
|---|---|
| action | `give`, `take`, `set`, or `reset` |
| `target` | A player, or a government account name (give/take/set only) |
| `amount` | Required for give/take/set (`reset` takes none) |

`give`, `take`, and `set` also accept a **government account** name as the target (handy
for topping up a department or a faucet account); a same-named government account takes
precedence over a player. `reset` is players-only. The affected player is notified
in-game when their own balance is changed this way.

## Tax cycles — `/tax`

Inspect and test the automatic tax cycles.

```text
/tax status                 # config + next-fire times for every cycle
/tax trigger weekly         # fire a cycle now (testing)
```

| Argument | What it is |
|---|---|
| action | `status`, or `trigger` |
| `cycle` | `daily`, `weekly`, `monthly`, or `balance <player>` (for `trigger`) |

`/tax status` is the quickest way to confirm the cycles are scheduled and which plugins
are registered with each. `/tax trigger` runs one early without waiting for the
schedule.

## Salaries

Government salaries are **configured in Treasury's `config.yml`**, under `salaries:` —
there's no in-game command to change them. Each entry maps a role (LuckPerms group) to an
amount:

```yaml
salaries:
  enabled: true
  # The GOVERNMENT account salaries are paid from.
  government-account: "DCGovernment"
  # Seconds between payout cycles (900 = 15 minutes).
  interval: 900
  # LuckPerms group name -> salary amount (matched case-insensitively).
  amount:
    president: 75.0
    senator: 65.0
    policeofficer: 50.0
```

Notes for whoever maintains this:

- Group names are matched **case-insensitively**, and a player in several salaried groups
  is paid the **highest** amount, never the sum.
- Payments come from the configured **GOVERNMENT account**, so make sure it can cover
  payroll (the default `DCGovernment` is an unlimited faucet).
- It relies on LuckPerms to know each player's groups, and players are only paid while
  **online**.

> [!NOTE]
> Salary table changes take effect on a config reload or server restart.

The player-facing behaviour (who gets paid, the chat notification) is in
[Government salaries](/docs/guides/salaries).

## Moving and inspecting money — `/treasury admin`

For one-off corrections that cross account types, `/treasury admin` moves money between
any two accounts and inspects balances directly.

```text
/treasury admin transfer <fromType> <from> <toType> <to> <amount> [reason]
/treasury admin balance <type> <id>
/treasury admin info <type> <id>
```

`<type>` is the account kind (e.g. `PERSONAL`, `BUSINESS`, `GOVERNMENT`). Unlike `/eco`,
`transfer` moves existing money rather than minting or destroying it, so the totals stay
balanced. Operators only.

## See also

- [Managing Explorer access](/docs/admin/roles) — granting admin/government roles.
- The full [Treasury reference](/docs/reference/treasury#admin-commands).
