# REST admin webhook delivery review (PAR-499)

`AdminWebhookDeliveryTest` covers admin subscription creation and the URL,
secret, active-state and deletion mutators through actual HTTP delivery. Existing
`AdminApiTest` checks stored state, auth gates and selected invalid inputs;
`WebhookDeliveryTest` checks public subscription delivery and signature details.

The receiver now captures the request path in addition to headers and raw body.
This exposes a wrong delivery destination even when both endpoints are handled
by the same local HTTP receiver. HMAC is recomputed independently using Java
crypto over the received raw bytes and the configured secret.

| Behavior | Observable assertion |
| --- | --- |
| Admin-created PERSONAL subscription | Real debit event with exact account, counterparty, amount, memo, path and HMAC |
| Wrong owner scope on URL/secret/active/delete | Zero affected rows; following event still arrives at original path with original signature |
| Admin URL and secret update | Next transaction arrives at new path and verifies with new secret |
| Pause | Control subscription receives the transaction; paused subscription has no matching event |
| Resume | A later transaction arrives at the changed path with changed signature |
| Delete and repeat delete | Affected counts 1 then 0; control receives next transaction while deleted subscription has no matching event |

Five actual transfers leave sender 9995 and recipient 10005. Negative-delivery
checks are bounded observations after a positive control for that transaction,
not a claim that no delayed event could ever arrive. Already-enqueued deliveries,
retry/failure handling, concurrent mutation, firm subscriptions, invalid-secret
boundaries and account ownership changes remain gaps. The target is local and
explicitly allowed by the test profile, not an external production endpoint.
See `results.md` for execution evidence. No production behavior is changed.

## Transient delivery failure and retry

`WebhookReceiver.failFirstAttempt` installs a path-scoped, AutoCloseable response
rule: return 503 on the first request for each delivery ID and 200 thereafter.
The receiver records its response status. Per-delivery tracking prevents an
unrelated starting-grant event from consuming the payment's injected failure.
Closing the rule restores normal responses; other paths remain unaffected.

`WebhookRetryTest` uses the normal dispatcher retry settings. After a real 23.45
payment, it observes HTTP 503 and the persisted PENDING row with one attempt,
HTTP 503 error and a future next-attempt time. It then waits for DELIVERED with
two attempts, HTTP 200 and cleared error. Both HTTP requests must have identical
raw payloads, delivery headers and signatures. Sender/recipient balances remain
9976.55/10023.45, with exactly one transaction and two postings initiated by the
sender. This demonstrates transient recovery, not maximum-attempt exhaustion,
subscription auto-disable, process-restart recovery or an exact backoff schedule.

## Configuration changes while a delivery is pending

`WebhookRetryTest.pendingRetryUsesUpdatedTargetAndSecretWithoutChangingDeliveryIdentity`
characterizes the dispatcher's current-subscription lookup: after a real first
503 and persisted PENDING state, admin changes URL and secret before the normal
retry becomes due. The second request must reach the new path with an HMAC
computed from the new secret, while retaining the exact payload and delivery
identity. Exactly two requests and persisted attempts=2 establish that the same
queued delivery retried. A 7.89 payment still has one ledger transaction/two
postings and balances 9992.11/10007.89. This tests changes completed before the
retry is selected, not mutation during an already-running HTTP request or an
atomic URL/secret update (the two admin calls are separate).

## Exhaustion, automatic disabling and recovery

The dedicated `webhook-failure` profile uses max-attempts=3,
failure-threshold=2 and retry-base/max=1/2 seconds. `WebhookExhaustionTest`
waits for the dispatcher cursor to pass starting grants before subscribing.
Two sequential payments each receive three actual HTTP 503 responses and become
FAILED with persisted attempt/status/error evidence. The first leaves the
subscription active with one consecutive failure; the second disables it and
sets disabled_at. Closing the receiver fault and reactivating through the admin
API lets a third payment deliver with 200 and resets consecutive failures to
zero. The two terminal failures remain recorded. Three payments produce exactly
three ledger transactions/six postings, with balances 9997/10003.

This exercises real exhaustion and health transitions with explicit shorter
configuration. Default numeric thresholds/backoff, permanent network timeout,
restart recovery and concurrent health updates are not established. The profile
is isolated because its retry settings affect every subscription on that stack.

## Consecutive versus historical failures

`WebhookExhaustionTest.successfulDeliverySeparatesFailuresSoHistoricalFailuresDoNotDisable`
runs failure/success/failure/success without admin activation or direct health
writes. Each failure exhausts three actual 503 responses and increments health
to one; each intervening success is delivered once with HTTP 200 and resets
health to zero. Despite two historical FAILED deliveries, the subscription
remains active and disabled_at stays null. Two DELIVERED records remain alongside
the failures. Four payments produce four transactions/eight postings and balances
9996/10004. This distinguishes consecutive failures from lifetime failure totals;
concurrent completion ordering remains untested.

## Pause an already queued retry

`WebhookRetryTest.pausedOverdueRetryRemainsPendingAndResumesWithSameIdentity`
receives an actual HTTP 503, confirms the delivery is PENDING with one attempt
and a future retry time, then pauses the subscription through the admin API.
After the normal retry becomes overdue, a new payment reaches a separate control
subscription. This proves the dispatcher remains active after the due time; the
paused delivery must still have one attempt and no second received HTTP request.

Reactivation must deliver that same overdue event successfully with two total
attempts, the identical raw payload, delivery header and signature. Its prior
HTTP error clears. The original seven-unit payment plus one-unit control payment
must leave balances 9992/10008, exactly two initiated transactions and four
postings. This checks queued pause/resume, not cancellation of an already selected
or in-flight HTTP attempt. It uses the default retry schedule, without editing
queue timestamps or replacing the dispatcher.

## Delete an already queued retry

`WebhookRetryTest.deletingSubscriptionRemovesPendingRetryBeforeItBecomesDue`
receives a real 503 and observes PENDING/one attempt with a future retry. It reads
the remaining interval from MariaDB, deletes the subscription through the admin
API and requires the pending delivery row to disappear through the foreign-key
cascade. Repeated deletion must report zero affected subscriptions.

After the original retry deadline plus one second, a new payment is delivered to
a fresh control subscription. The deleted subscription must still have only its
original failed request for the old payment and no request for the new payment;
the queued row remains absent. Balances 9992/10008, two initiated transactions and
four postings prove deletion/retry processing does not repeat payments. This is
bounded post-deadline evidence for a deletion performed before dispatch, not
cancellation of an HTTP request already in flight. Queue timestamps are only read.
