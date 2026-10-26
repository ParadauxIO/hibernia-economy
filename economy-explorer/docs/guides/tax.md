---
title: How tax works
order: 9
description: The taxes that keep the economy balanced — what you pay, when, and how to keep an eye on it.
---

# How tax works

The server collects tax to keep money circulating and to help fund the government.
You don't run any commands for it — tax is applied automatically. This page explains
what gets taxed and when, so nothing comes as a surprise.

## Personal balance tax

The money you're holding is taxed a small amount each week. A few things to know:

- It's **based on your balance** — the more you're sitting on, the more tax applies,
  and higher balances are taxed at a higher rate.
- It's applied when you **log in**, prorated by elapsed time since your previous
  login, including time offline. A longer absence can therefore mean a larger charge.
- Spending and using your money normally is the point — parking a huge balance and
  never touching it is what the tax gently discourages.

The rates on this server right now. Your **whole** balance pays the rate of the band
it falls in, and the weekly rate is scaled to the time since your last login:

```live
balance-tax
```

> [!NOTE]
> When tax is taken, you'll see a message in chat telling you the amount and what it
> was for. You can also see every deduction in your history with `/transactions`.

## Business tax

Firms pay a weekly tax on the money in their accounts, on the same idea — larger
balances pay more. If you run a [business](/docs/guides/setting-up-a-business), check
where it stands:

```text
/business tax info Acme
```

That shows the firm's current balance, its tax status, and the estimated weekly amount.
Firms can be made tax-exempt by an administrator.

## Income tax

Some kinds of income have a little tax taken at the source as you earn it, rather than
later. When that happens it's handled automatically — the amount you receive is already
net of the tax. Unlike balance tax, this one is **silent** — there's no chat message
for each deduction (it would be too noisy), but it still shows up in `/transactions`.

The rate depends on what paid you — each row is the plugin the money came through:

```live
income-tax
```

## Keeping an eye on it

- Your own deductions: `/transactions` (or **My finances** on the
  [Explorer](/docs/guides/using-the-explorer)).
- A firm's: `/business transactions Acme`.
- The big picture of where tax flows: the [Money flow](/money-flow) view on the
  Explorer.

## Where to go next

- The flip side — money the government pays out: **[Salaries](/docs/guides/salaries)**.
- How the different accounts hold this money: **[Accounts & money](/docs/concepts/accounts-and-money)**.
