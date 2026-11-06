---
title: Direct debits
order: 4.5
description: Let a business charge your personal account, up to a limit you approve, and cancel any time.
---

# Direct debits

A direct debit lets a business take payments from your personal account without you
paying each time. It only works after you approve it, it can never take more than
the limit you approved, and you can cancel it whenever you like.

> [!NOTE]
> Direct debits are being rolled out. Until staff switch them on, every
> `/business debit` command replies that they aren't available yet.

There are two kinds:

- **Recurring**: a fixed amount taken automatically once a day, week or month. Good
  for subscriptions and rent.
- **On demand**: the business charges you whenever it needs to, but the total in one
  day, week or month can't go over the limit you approved. Good for a tab at a shop
  or a gym that bills per visit.

Periods follow server time (UTC). Days start at midnight, weeks start on Monday and
months start on the 1st.

## If you're the customer

When a business asks you to set one up, you'll see what it wants to take and what
for, with **[Approve]** and **[Decline]** buttons. You can also answer with commands:

```text
/business debit accept 12
/business debit reject 12
```

Requests expire after 7 days. See everything you've approved or been asked to approve:

```text
/business debits
```

Cancel one at any time. Nothing more is taken after you cancel:

```text
/business debit revoke 12
```

You get a message whenever a payment is taken. Payments come from your personal
account only, never a business you belong to.

> [!WARNING]
> If a recurring payment fails because you don't have enough money, it's retried
> hourly, up to 3 times that period. After 3 periods in a row without a payment, the
> direct debit is **suspended**. Nothing is taken until you resume it with
> `/business debit resume 12`. Missed payments aren't taken later.

## If you run a business

You need the owner, **ADMIN** or **FINANCIAL** role, the same as for paying people.
Money goes into your firm's default account. Always give a reference so the customer
knows what they're approving.

Ask for a recurring payment of 50 every week:

```text
/business debit propose recurring Acme Steve weekly 50 Weekly rent for plot 14
```

Ask to charge up to 200 a month, on demand:

```text
/business debit propose ondemand Acme Steve monthly 200 Bar tab
```

Once Steve approves, recurring payments are taken automatically, starting straight
away. For on-demand ones, charge when you need to. You can add an optional memo:

```text
/business debit charge Acme 12 35 Two drinks
```

The reply tells you how much of the limit is left this period. A charge that would
go over the limit is refused, and so is a charge Steve can't afford.

See your firm's direct debits, and cancel one:

```text
/business debit list Acme
/business debit cancel Acme 12
```

To change the amount or limit, cancel the old direct debit and propose a new one.
The customer approves the new terms.

Payments show in both accounts' transaction history as `Direct debit: <firm> — <reference>`.
They also reach any [webhooks](/docs/guides/webhooks) your firm has set up, tagged with
the `business-direct-debit` plugin system.
