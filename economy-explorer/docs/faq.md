---
title: FAQ & troubleshooting
order: 0
description: Quick answers to the most common money, shop, business, salary, and account-linking questions.
---

# FAQ & troubleshooting

Short answers to the things players ask most. If your question isn't here, the
[guides](/docs/guides/getting-started) go into more depth.

## Money

### Why did money disappear from my balance?

The usual reasons are **tax** or a **fine**. The server charges a small
[balance tax](/docs/guides/tax) (applied when you log in, based on how much you're
holding), and government can issue [fines](/docs/guides/government-accounts). Every
deduction is listed in `/transactions` with a note saying what it was — start there.

### I sent a payment to the wrong person. Can I get it back?

Payments go through immediately and **can't be reversed** for you. Ask the recipient to
send it back with `/pay`. Double-check the name and amount before confirming.

### How do I see where my money went?

`/transactions` shows your full history, and **My data** on the
[Explorer](/docs/guides/using-the-explorer) shows it visually once you've linked your
account.

## Salaries

### I'm not getting my government salary.

Check these:

- You must be **online** — salaries are paid on a timer and skip offline players.
- Your **role** must have a salary set; not every role does.
- If you hold several roles, you're paid the **highest one**, not the sum.

See [Government salaries](/docs/guides/salaries) for the full rules.

## ChestShops

### My shop isn't selling (or buying).

- **Selling to players:** the chest must actually contain the item, in stock.
- **Buying from players:** the shop owner (you, or the firm account) must have enough
  **money** to pay.
- A shop that's *low* on either still trades a **[partial amount](/docs/guides/chestshop-basics#partial-trades)** —
  it only stops entirely when a side hits zero. So "nothing happens at all" usually means
  an empty chest, a broke owner, or a wrong item/price on the sign.
- Stand in front of the sign and run `/shopinfo` to check the owner, item, and prices.

See [Selling with ChestShops](/docs/guides/chestshop-basics).

### Can I sell custom (Nexo) items in a shop?

Yes — custom items work exactly like vanilla ones. Hold the item and put `?` on the item
line (line 4), or run `/iteminfo` while holding it to see the name to type. Stock the
chest with the real custom item. See
[Selling custom items](/docs/guides/chestshop-basics#selling-custom-items).

### How do I make a shop pay my company instead of me?

Put the firm account's **shop code** (like `B:16`, from `/business account list`) on the
top line of the sign. Full steps:
[ChestShops for your firm](/docs/guides/chestshop-business).

## Businesses

### Why can't I withdraw or pay from my firm's account?

Spending a firm's money needs the **FINANCIAL** or **ADMIN** role **and** being an
**authorizer** on that account. Being a member only lets you *view* it. See
[Business accounts](/docs/guides/business-accounts) and
[Permissions & roles](/docs/concepts/permissions-and-roles).

### How do I hire someone?

Send `/business offer <firm> <player>`; they accept with
`/business offer accept <firm>` within 5 minutes. See
[Hiring & managing staff](/docs/guides/hiring-staff).

## The Explorer website

### I signed in but I can't see my own data.

Signing in isn't enough — you also have to **link** your Minecraft account. Open
[Link your account](/link), generate a code, and run `/treasuryapi ui link <code>`
in-game within 5 minutes. See [Using the Explorer](/docs/guides/using-the-explorer).

### My link code didn't work.

Codes **expire after 5 minutes**. Generate a fresh one and run the command again.

### A page says "Something went wrong."

Try a **refresh** first — that clears the most common cause (a page loaded during a
site update). If it keeps happening on a specific page, let an admin know what page and
what you were doing.

## Still stuck?

- Browse the [guides](/docs/guides/getting-started) for step-by-step walkthroughs.
- Look up a term in the [glossary](/docs/concepts/glossary).
- Check the [plugin reference](/docs/reference/treasury) for exact command syntax.
