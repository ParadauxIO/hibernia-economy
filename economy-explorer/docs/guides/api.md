---
title: Using the economy API
order: 12
description: Build apps against the economy — get an API key in-game, authenticate, and explore the endpoints in Swagger.
---

# Using the economy API

The economy has a **REST API** so you can build your own apps and integrations against
your Treasury accounts — check balances, read transaction history, and move money. This
guide gets you from nothing to your first authenticated request.

> [!NOTE]
> This page is for **developers**. If you just want to view your own data on the
> website, you don't need any of this — see
> [Using the Explorer](/docs/guides/using-the-explorer) instead.

## The interactive reference (Swagger)

Every endpoint, with request/response schemas and a built-in "try it out", lives in the
Swagger UI. Open the one for **your** server:

> [!TIP]
> **Open the API reference:**
> - [DemocracyCraft API reference](https://api.democracycraft.net/economy/swagger-ui/index.html)
> - [StateCraft API reference](https://api.mcstatecraft.com/economy/swagger-ui/index.html)
>
> Use the one matching the server you play on.

The base URL for requests is the same prefix:

| Server | Base URL |
|---|---|
| DemocracyCraft | `https://api.democracycraft.net/economy` |
| StateCraft | `https://api.mcstatecraft.com/economy` |

## Step 1 — Get an API key in-game

Keys are issued **in Minecraft**, not on the website. Each key is a token scoped to a
single account, so the API can only touch the account you issued it for.

For your **personal** account:

```text
/treasuryapi personal issue
```

The plugin replies with the new key's ID and the **token** itself (click it in chat to
copy). For a **firm** account, issue a business key instead:

```text
/treasuryapi business issue Acme
```

> [!WARNING]
> The token is shown **once**, when it's issued, and is never stored — it can't be
> retrieved later. Copy it somewhere safe. If you lose it, replace it with
> `/treasuryapi personal reissue <keyId>` (the old token stops working).

Manage your keys any time:

```text
/treasuryapi personal list            # your keys and their IDs
/treasuryapi personal revoke <keyId>  # kill a key immediately
```

Business keys have the same `list` / `reissue` / `revoke` subcommands under
`/treasuryapi business`. Firm staff can also run `/treasuryapi business list access` to
see the keys issued for firms they're employed at.

## Step 2 — Authenticate

Send the token as a **Bearer** token in the `Authorization` header on every request:

```text
Authorization: Bearer <your-token>
```

The token is a JWT scoped to one account and valid for **180 days**. Keep it secret — a
valid token can move that account's money.

## Step 3 — Make a request

For example, read an account's transaction history (replace the host with your server's
base URL and `<accountId>` with your account):

```bash
curl -H "Authorization: Bearer $TOKEN" \
  "https://api.democracycraft.net/economy/api/v1/accounts/<accountId>/transactions"
```

From there, the [Swagger reference](#the-interactive-reference-swagger) is the
authoritative list of endpoints and exact request bodies. The core capabilities are:

- **Transfers** — move money to a player or to a firm.
- **Transactions** — read an account's history.
- **Balance** — read an account's current balance.
- **Baltop** — `GET /api/v1/accounts/baltop?page=1&limit=10` lists active personal
  accounts ranked by balance, as in `/baltop` in-game. Any valid API key can read
  player UUIDs, names, account IDs and balances. The default rate limit is 10
  requests per minute per issuer, shared across their keys, including business
  keys (administrator rate-limit overrides still apply). Pages start at 1; the default
  page size is 10 and the maximum is 100. Balances are decimal strings. Ties
  are ordered by account ID, but balance changes between requests can move
  accounts between pages, so pagination does not provide a fixed snapshot.
- **Webhooks** — get a signed POST when the account moves money, instead of polling (see
  the [Webhooks guide](/docs/guides/webhooks)).
- **Token rotation** — refresh your token before it expires.

> [!NOTE]
> Requests are **rate-limited** per key. Swagger shows each endpoint's limit; if you go
> over, you'll get an HTTP `429` and should back off and retry.

## Keeping a token alive

Rather than re-issuing in-game every time, an app can **rotate** its token through the
API before the 180-day expiry — see the auth endpoints in Swagger. If a token is ever
leaked, revoke it immediately in-game with `/treasuryapi personal revoke <keyId>` and
issue a fresh one.

## Security checklist

- Treat the token like a password — it can spend the account's money.
- Never commit it to source control or paste it into a public channel.
- Use a **separate key per app** so you can revoke one without breaking the others.
- Revoke keys you no longer use.

## Where to go next

- The full endpoint reference and "try it out": the Swagger links
  [above](#the-interactive-reference-swagger).
- **[Webhooks](/docs/guides/webhooks)** — react to transactions in real time instead of
  polling.
- How accounts and money work behind the API:
  [Accounts & money](/docs/concepts/accounts-and-money).
