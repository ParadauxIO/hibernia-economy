---
title: Webhooks
order: 6
description: The admin webhooks console — list, search, and manage every webhook on the server, and register one for any account.
---

# Webhooks (admin)

Players manage their own webhooks at [My data → Webhooks](/me/webhooks). The admin
console at **[Admin → Webhooks](/admin/webhooks)** is the fleet-wide view across **every**
account and firm.

For what webhooks are and how delivery, signing, and Discord embeds work, see the player
guide: [Webhooks](/docs/guides/webhooks).

## Listing & searching

The page lists every webhook subscription, newest first, with its owner, scope, status,
consecutive-failure count, and source. You can:

- **Search** by target URL, owner name, account or firm name, or any id (subscription /
  account / firm).
- **Filter** by:
  - **Scope** — account-scoped vs firm-scoped.
  - **Status** — *active*, *paused* (manually disabled), or *auto-disabled* (paused by the
    system after repeated delivery failures).
  - **Source** — *explorer* (created on the website) vs *API key* (created in-game via a
    REST API key).

## Creating a webhook for an account

Enter an **account id** and a public `https://` URL, then **Create**. The webhook is
account-scoped and attributed to that account's **owner**, so:

- It behaves exactly like one the owner created themselves, and
- the owner can also see and manage it under their own [My data → Webhooks](/me/webhooks).

The signing secret is shown **once** on creation — copy it if the consumer needs it.
A Discord webhook URL is auto-detected and delivered as a rich embed, same as anywhere
else.

> [!NOTE]
> The webhook is visible to the account's owner. There's no "covert" oversight webhook
> today — if you need one the owner can't see, raise it as a feature request.

## Per-row actions

- **Pause / Enable** — re-enabling clears the failure counter and the auto-disabled flag.
- **Delete** — removes the subscription and its delivery history; no further events are
  sent.

> [!CAUTION]
> Creating, pausing, and deleting webhooks here are privileged actions and are written to
> the [audit log](/docs/admin/explorer-administration#audit-evidence). Prefer pointing a player at their own
> [My data → Webhooks](/me/webhooks) page when they can self-serve.

## Delivery incident procedure

Access needs `webhooks.read.all`; creation, update and deletion separately require
`webhooks.create.all`, `webhooks.update.all` and `webhooks.delete.all`. Admin API
configuration and its SERVICE token must also work. The fleet console does not
have every owner self-service control; use the owner's detail page for their
secret rotation and delivery history.

1. Record subscription ID, account/firm, active/auto-disabled state and the last
   successful delivery. Keep the endpoint URL/secret private. Check that the
   expected ledger transaction actually settled after the subscription existed.
2. Inspect recent attempts: distinguish receiver HTTP errors, timeout/DNS/TLS,
   signature rejection and an inactive subscription. Receiver URLs must remain
   public HTTPS; the dispatcher revalidates destinations. Redirects are not a way
   to reach an internal endpoint.
3. Verify REST `WEBHOOK_ENABLED`, DB connectivity, ingest cursor, pending delivery
   state and dispatcher logs. With multiple REST replicas, configure shared
   rate-limit Redis so the dispatcher lease has one holder. Do not reset the
   cursor or enable independent dispatchers to force delivery.
4. Fix the receiver/configuration before clicking Enable. Enable resets the
   subscription failure counter and auto-disabled flag; it does not guarantee
   replay of events skipped while paused or deliveries whose attempts ran out.
5. Verify a new controlled transaction reaches the intended receiver once in its
   business processing, even if delivery is retried. Reconcile the missed interval
   with the authorized transaction history/feed. No manual replay-all button is
   provided.

Shipped defaults: 2-second polling, 3-second settlement lag, ingest batch 500,
delivery batch 100, concurrency 8, connect/request timeouts 5/10 seconds, at most
5 attempts per delivery, exponential backoff from 30 seconds capped at 3600,
and auto-disable after 15 consecutive subscription failures. Redis lease TTL
defaults to 15 seconds; API-key subscription limit is 20. Overrides are listed in
[service configuration](/docs/admin/config-services).

For a leaked secret, pause the subscription, rotate through owner self-service,
update the receiver and verify signatures before resuming. Deleting a subscription
also removes delivery history: preserve needed evidence first. Generic receivers
must reject missing/malformed signatures and deduplicate by `deliveryId`, not only
`txnId` (a firm transfer can have multiple account postings).
