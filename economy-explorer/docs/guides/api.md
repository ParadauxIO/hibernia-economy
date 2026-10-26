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

The player API's request/response schemas and a built-in "try it out" live in the
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
personal account, an entire firm, or one account under a firm. Business keys require
the proprietor to issue them.

For your **personal** account:

```text
/treasuryapi personal issue
```

The plugin replies with the new key's ID and the **token** itself (click it in chat to
copy). For a **firm** account, issue a business key instead:

```text
/treasuryapi business issue Acme
```

To restrict the key to a specific linked account, including a subaccount, append
its Treasury account ID:

```text
/treasuryapi business issue Acme 42
```

This key can debit account #42, read its transaction history/feed, rename it, and
subscribe to its webhooks. Transfers still require `fromAccountId: 42`.
`GET /api/v1/firms/me/accounts` returns only that linked account. Sibling accounts
are forbidden even if they belong to the same firm. Firm updates and employee/role
lists require a firm-wide key. Public reads (including balances and firm profiles)
remain available. Reissuing or rotating a key preserves its account scope; issue a
new key to select a different scope. Omitting the account ID retains firm-wide access.

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

The token is a JWT scoped to its personal account or firm and valid for **180 days**. Keep it secret — a
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
> Authenticated limits are keyed by **issuer, route, and effective limit**, so
> issuing extra keys does not give each app an independent allowance. Anonymous
> calls to public endpoints use an IP-based allowance. On HTTP `429`, honor `Retry-After` in seconds;
> retry money requests only with the same idempotency key and body (see below).

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

## Endpoint map

Paths below are relative to your server's base URL. Every endpoint uses the
`/api/v1` prefix; the table spells it out to avoid ambiguity. Consult Swagger for
exact bodies, query filters and response schemas.

| Capability | Endpoints and access |
|---|---|
| Token identity/rotation | `GET /api/v1/auth/me`, `POST /api/v1/auth/rotate`; valid token required. |
| Player lookup | `GET /api/v1/accounts/by-player?name=Steve` or `?uuid=<uuid>` (exactly one); valid token. Name lookup requires the server to have seen the player. |
| Account reads | `GET /api/v1/accounts/{accountId}/balance` is available to authenticated callers; `GET /api/v1/accounts/{accountId}/transactions` and `GET /api/v1/accounts/{accountId}/transactions/feed` require the appropriate account/firm access. |
| Transfers | `POST /api/v1/transfers`, `POST /api/v1/transfers/to-player`, `POST /api/v1/transfers/to-firm`; valid token and source-account authority. |
| Firm public information | `GET /api/v1/firms/{firmName}` and `GET /api/v1/firms/{firmName}/balance`; authenticated access. |
| Your firm | `GET /api/v1/firms/me`, `GET /api/v1/firms/me/accounts`, `GET /api/v1/firms/me/employees`, `GET /api/v1/firms/me/roles`; business key. `PATCH /api/v1/firms/me` and `PATCH /api/v1/firms/me/accounts/{accountId}/display-name` update allowed firm/account fields. |
| Public market | `GET /api/v1/chestshop/shops`, `GET /api/v1/chestshop/items`, `GET /api/v1/chestshop/items/{itemKey}`, `GET /api/v1/chestshop/stats`; no token required, IP-rate-limited. |
| Public tax rates | `GET /api/v1/config/tax`; published personal balance/source-income settings, no token required. These are not Realty or ChestShop rate settings. |
| Webhooks | `/api/v1/webhooks`; authenticated subscriptions scoped to the key's account or firm. |

The service administrative endpoints are for the Explorer backend and require a
SERVICE token. Do not embed such a credential in a player application.

If a player name also matches a government account, a bare-name lookup or transfer
can return `AMBIGUOUS_NAME`. Resolve the intended player by UUID instead of guessing
which wallet the name denotes. A known offline or Bedrock player can be resolved
from the stored player directory; an unknown name is not proof of an existing account.

## Safe transfers and retries

Use a distinct `Idempotency-Key` header for each logical transfer and retain it
with the request body. If the connection fails, retry the **same key and identical
body**. The key is scoped to the source account: do not reuse it for a different
payment, destination, amount or memo. A matching replay returns the original
transaction; a conflicting request receives HTTP `409`. Without a key a retry
can create another payment.

Treat amounts as exact decimal values/strings as specified by the schema, not
binary floating-point money. Record the returned transaction ID. After an
uncertain result, reconcile transaction history before inventing a new key.
On `429`, honor `Retry-After`; money endpoints may return `503` if rate-limit
infrastructure is unavailable. Back off while preserving the operation identity.

## Polling the transaction feed

`GET /api/v1/accounts/{accountId}/transactions/feed?since=0&limit=100`
returns settled transactions oldest first with `nextCursor` and `hasMore`.
Persist the cursor **after** processing the batch; use it as `since` on the next
request. Drain while `hasMore` is true, then wait before polling again. The default
batch is 100 and the maximum 1000. A configurable settlement lag (default three
seconds) deliberately withholds the newest settlements.

Keep a separate cursor per account and deduplicate processing across restarts.
The [webhook guide](/docs/guides/webhooks) describes signed push notifications;
use history/feed reconciliation when a receiver has been offline or deliveries
have exhausted their retries.

## Integrating another server plugin

Paper plugins use the in-process `io.paradaux:treasury-api` and
`io.paradaux:business-api` libraries, obtained from Bukkit's service registry.
These are separate from REST tokens and the TreasuryAPI key-issuer plugin.
Match the library and installed provider versions; use their public interfaces
instead of querying another plugin's mappers or writing economy tables.

The [Java API reference notes](https://github.com/ParadauxIO/hibernia-economy/blob/develop/wiki/components.md)
cover typed firm lookup, account resolution, batch reads and shop aliases.
`business.firms().getAccountIds(firmId)` returns linked account IDs;
`getDefaultAccountId(firmId)` returns the configured default;
`getFirmIdByAccountId(accountId)` and `getFirmByAccountId(accountId)` resolve the
reverse relationship. A proprietor may own multiple firms: querying their Treasury
accounts is not a substitute for finding a firm's linked accounts.

These lookups do not create accounts or grant permission. Singular lookups can
return null; linked-account lists can be empty. Removed links are excluded, but
archived firms can retain links, so check account status and authority separately.
Upgrade the provider with the library: an older provider's default `getAccountIds`
throws rather than returning an incomplete list. Use
`FirmApi.resolveShopAlias(alias)` for live business shop aliases and preserve
normal access checks before spending.

### Reacting to a settled shop purchase

ChestShop publishes `io.paradaux.chestshop.api.events.ChestShopPurchaseEvent`
after a customer's successful **BUY**, including a free purchase. This synchronous,
non-cancellable Bukkit event does not fire for SELL, cancelled or failed settlement.
`getPlayer()` identifies the buyer; `getReceipt()` returns an immutable receipt with
`tradeId()`, `buyerId()`, exact `price()` and `items()` containing material and actual
quantity. It does not expose custom-item metadata, shop ownership or ledger linkage.

Use this single notification path for a purchase objective; also consuming legacy
transaction or sale events can count the same purchase twice. The trade UUID can
help a consumer deduplicate, but delivery is in-process with no durable replay or
exactly-once reward guarantee. A listener failure cannot undo settled funds.

Keep the integration optional and use a compile-only dependency. ChestShop does
not install Typewriter quests: the separate consumer must check active quest
criteria, persist completion and reset state for new attempts. See the
[settled purchase API contract](https://github.com/ParadauxIO/hibernia-economy/blob/develop/wiki/integrations/settled-purchases.md)
for receipt and consumer lifecycle details.
