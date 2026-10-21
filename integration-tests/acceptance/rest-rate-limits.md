# Live rate-limit override review (PAR-499)

`RateLimitOverrideLifecycleTest` exercises admin PUT/DELETE overrides through
actual `/auth/rotate` requests. It warms the base-limit cache, applies multiplier
0.20 (one request/minute), then 0.40 (two requests/minute), and clears it back to
the five-request base. Exact limit/remaining headers accompany every success;
exhaustion must return 429 RATE_LIMIT_EXCEEDED, remaining zero and Retry-After
between one and sixty seconds, without changing the stored JTI.

Two keys owned by the same player get the same overridden capacity but consume
a shared bucket. Another owner's key retains the base capacity and its own
remaining count. An invalid zero multiplier must leave the active limit and
bucket intact. Removal and repeated removal succeed. The test characterizes
capacity-keyed buckets: returning to a prior capacity resumes that owner bucket's
remaining tokens rather than resetting it. Neither balance nor ledger checkpoint
changes throughout this credential-only journey.

Existing `RateLimitTest` covers base-capacity exhaustion; `AdminApiTest` covers
stored overrides, invalid zero and auth gates. Remaining work includes rounding
and field boundaries, separate route buckets, timed refill, BUSINESS/SERVICE
behavior, anonymous-IP identity, Redis/multiple API replicas and concurrent
updates. The local stack uses the default in-memory provider, not Redis.
See `results.md` for execution evidence.

## Storage and effective-capacity boundaries

`RateLimitBoundaryTest` checks these literal cases against both MariaDB and a
real `/auth/rotate` response:

| Input multiplier | Stored DECIMAL(6,2) | Effective limit on base 5 |
| --- | --- | --- |
| 0.005 | 0.01 | 1 |
| 0.10 | 0.10 | 1 |
| 0.30 | 0.30 | 2 |
| 9999.994 | 9999.99 | 50000 |

The lower/upper input edges are explicit in `AdminRateLimitService`; effective
limits are rounded half-up and clamped to at least one. Each case checks the
remaining count after its first accepted request and unchanged money/checkpoint.
A separate rejection journey starts with multiplier 2 and a 255-character note.
Missing/null/negative/too-small/too-large multipliers and a 256-character note
must return INVALID_BODY, preserving multiplier, note and updated-by identity.
Unauthorized PERSONAL PUT/DELETE similarly leave the row intact. A subsequent
rotation still advertises limit 10/remaining 9. Timed refill, independent-route
budgets, Redis/multi-replica behavior and concurrent updates remain unverified.

## Refill and route isolation

`RateLimitTest` now extends exhaustion into recovery. While rotation is throttled,
the same bearer can read its account's transaction history with a positive
remaining budget. The rejected rotation and history read preserve the stored JWT
ID. After the advertised whole-second retry delay plus one second for truncation,
rotation succeeds, updates the JWT ID and reports four remaining tokens: one
request consumed a token from the refilled five-token bucket. This characterizes
the implemented interval refill; comments in RateLimit, BucketProvider and
CaffeineBucketProvider instead describe gradual refill. Both provider builders
use refillIntervally, so those descriptions do not match the current policy.
Balance 10000 and the post-key-issuance ledger checkpoint remain unchanged.

This exercises the normal in-memory backend's timed refill and separation between
rotation and history routes. It does not certify exact Retry-After rounding,
Redis providers, multiple replicas, all route pairs or concurrent refill races.
