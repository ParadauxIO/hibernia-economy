---
title: Webhooks
order: 13
description: Get a POST to your own endpoint (or a Discord channel) whenever your account or firm moves money — set one up on the site, no code required.
---

# Webhooks

A **webhook** sends an HTTP `POST` to a URL you choose every time money moves on an
account you watch — so your bank, bot, dashboard, or Discord channel reacts the moment a
transaction settles, instead of polling for changes.

You don't need to run a server to use them: point one at a **Discord channel** and you'll
get a nicely-formatted message per transaction with zero code. If you *are* building
something, every delivery is signed so you can trust it came from us.

> [!NOTE]
> Webhooks are **substrate** — the network provides the signal; what you build on top
> (a player bank, a tax bot, an alert feed) is up to you.

## What you can watch

Each webhook is scoped to one of:

- **Your personal account** — fires on every settled transaction touching it.
- **A firm you have financial access to** — fires on transactions touching any of that
  firm's accounts. You need the **FINANCIAL** or **ADMIN** role in the firm (the same
  access that lets you see its finances).

## Set one up on the site (no API key)

1. Sign in and [link your Minecraft account](/docs/guides/using-the-explorer).
2. Go to **[My data → Webhooks](/me/webhooks)**.
3. Pick the **scope** (your personal account, or a firm you manage).
4. Paste your endpoint — it must be a public **`https://`** URL.
5. Click **Create**. The **signing secret is shown once** — copy it now (you'll need it
   to verify deliveries; if you lose it, rotate the secret later).

That's it. The next settled transaction on that account/firm will arrive at your URL.

> [!TIP]
> Prefer to manage webhooks programmatically? You can also create them through the REST
> API (`/api/v1/webhooks`, scoped to an API key) — see
> [Using the economy API](/docs/guides/api) and the Swagger reference.

## Send it to Discord (the easy way)

Paste a **Discord webhook URL** (the one from a channel's *Integrations → Webhooks*) as
the endpoint. We detect it automatically and deliver a **rich embed** — credit/debit, the
amount, the account, and the memo — instead of raw JSON. No code, no server: the
transaction shows up as a tidy message in your channel.

> [!TIP]
> On the create form you'll see a "Discord webhook" hint and a **Discord** badge on the
> webhook once it's saved, so you know it'll arrive as an embed.

## What a delivery looks like

For a normal (non-Discord) endpoint, the body is JSON:

```json
{
  "event": "transaction",
  "deliveryId": 4821,
  "subscriptionId": 17,
  "accountId": 55,
  "transaction": {
    "postingId": 9876543,
    "txnId": 1782333,
    "amount": "3.47",
    "memo": "wages",
    "message": "siven01 sold x8 Feather to GovInterior",
    "settledAt": "2026-06-14T12:30:57Z",
    "initiatorUuid": "f7e6…",
    "pluginSystem": "ChestShop",
    "counterparty": {
      "accountId": 812,
      "accountType": "GOVERNMENT",
      "displayName": "GovInterior",
      "ownerUuid": "0c4a…"
    }
  }
}
```

`amount` is the signed change to **your** account (positive = money in, negative = out).
`counterparty` is the account on the other side of the transaction: its id, type
(`PERSONAL`, `BUSINESS`, `GOVERNMENT` or `SYSTEM`), display name and owner UUID. It is
`null` only if the transaction has no other side. The same `counterparty` object appears on
every item from the transaction history and feed endpoints.
The request also carries these headers:

| Header | Meaning |
|---|---|
| `X-Treasury-Event` | always `transaction` |
| `X-Treasury-Delivery` | the unique delivery id (also in the body) |
| `X-Treasury-Signature` | `sha256=<hmac>` — see below |

## Verifying a delivery

Anyone could POST JSON at your URL, so **verify the signature** before trusting it.
We sign the raw request body with **HMAC-SHA256** using your secret and send it as
`X-Treasury-Signature: sha256=<hex>`. Recompute it and compare:

```js
import crypto from 'node:crypto';

function isValid(rawBody, signatureHeader, secret) {
  const expected = 'sha256=' + crypto.createHmac('sha256', secret).update(rawBody).digest('hex');
  // constant-time compare
  return crypto.timingSafeEqual(Buffer.from(signatureHeader), Buffer.from(expected));
}
```

> [!CAUTION]
> Verify against the **raw bytes** of the body, exactly as received — don't re-serialize
> the JSON first, or the hash won't match. Reject anything that doesn't verify.

Discord deliveries are the exception: they carry no signature (Discord can't verify it,
and the body is Discord's embed shape, not our event) — so don't expect one there.

## Reliability

- **Settled only.** A transaction is delivered once it has fully settled, so you never
  get a half-finished transfer.
- **Retries.** A failed delivery (non-`2xx`, timeout, connection error) is retried with
  an increasing backoff. A `429` from Discord is treated the same way.
- **Auto-disable.** After many consecutive failures a webhook is automatically paused so
  a dead endpoint doesn't pile up forever. Fix your endpoint and **re-enable** it.
- **At-least-once.** A delivery can, rarely, arrive more than once — use the
  `deliveryId` (or `txnId`) to de-duplicate on your side.

## Managing your webhooks

On **[My data → Webhooks](/me/webhooks)** you can:

- **Pause / Enable** a webhook (re-enabling clears the failure count).
- **Rotate the secret** — issues a new signing secret (shown once); update your endpoint
  to match.
- **Delete** it.
- Open a webhook to see its **delivery health** — recent attempts with their HTTP status,
  retry timing, and the last error — so you can tell at a glance whether it's working.

## Where to go next

- [Using the economy API](/docs/guides/api) — issue an API key and manage webhooks (and
  everything else) programmatically.
- [Accounts & money](/docs/concepts/accounts-and-money) — how the transactions behind
  these events work.
