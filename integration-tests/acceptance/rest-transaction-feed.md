# Live transaction-feed cursor review (PAR-499)

`TransactionFeedTest.feedPagesByLimitAndCursor` now exercises a changing settled
feed. Three real payments of one, two and three are made. The client reads a
size-two first page and saves the second transaction's cursor. A fourth payment
of four arrives and settles before page two; that page must contain exactly the
third and fourth transaction, with correct debit amounts, memo, counterparties,
input/output cursor and hasMore=true for a completely full final page. The DTO
contract defines hasMore as a full-page hint, not proof of another row.

Polling from the fourth cursor then returns an empty page, preserves the input
cursor, returns a null nextCursor and hasMore=false. After a fifth payment settles,
the same fourth cursor must return only that new transaction, amount minus five
and its exact memo. Replaying from the original cursor must return all five IDs
once in ascending order. Balances are 9985/10015 and reads do not advance the
post-payment ledger checkpoint.

The existing class also checks sender/recipient attribution, caught-up responses,
foreign/unknown account handling, authentication and invalid parameters. Payments
are real HTTP transfers on the Paper-backed account database; settlement is awaited
through the real feed. This does not force out-of-order transaction commit, prove
an exact settlement-time boundary, exercise concurrent readers or establish every
business/government role transition.

## Force rotation, reissue and revocation

`AdminKeyLifecycleTest` now checks the feed alongside spending and history. The
original personal key reads its exact settled debit and saves the returned cursor.
Force rotation denies that bearer with TOKEN_SUPERSEDED at both cursor zero and
the saved cursor. Real Paper reissue restores a usable bearer, which reads its
new exact settled debit; the original bearer remains invalid. Repeated admin
revocation denies the replacement bearer with TOKEN_REVOKED on both feed cursors.
All denials preserve balances and the ledger checkpoint through the shared
lifecycle assertions. This checks request-time invalidation, not an in-flight
request race or business/government membership changes.

## Business key scope through reissue

`BusinessKeyLifecycleTest` now checks whole-firm and reserve-account-scoped keys
against the feed. Both read an exact settled reserve debit (ID, signed amount,
counterparty, account and next cursor) before force rotation and after real Paper
reissue. The scoped key receives 403 on the primary sibling account's feed before
and after reissue; the whole-firm key receives 200. Superseded and revoked bearers
receive the corresponding 401 error at both cursor zero and a saved transaction
cursor. Existing exact balance, account-link and checkpoint assertions cover all
rejected operations. This verifies token scope persistence, not staff membership
changes or a concurrent request during invalidation.

## Live government membership with an unchanged personal token

`FeedMembershipLifecycleTest` funds a government account with a real 23.45
payment, then uses the same personal bearer through no access, VIEWER, MEMBER,
removal and MEMBER regrant. No access and VIEWER must receive 403/FORBIDDEN for
history and both initial/saved feed cursors. MEMBER and regrant must return the
exact settled credit, transaction ID, memo and next cursor in feed, and the same
transaction in history. This follows the documented account_read_access_api view:
VIEWER is deliberately excluded even though in-game/explorer reading is broader.

The key JWT ID and revocation state remain unchanged throughout. Government,
funder and member balances remain 23.45/9976.55/10000 and authorization/read
operations do not advance the ledger checkpoint. All role changes use real Paper
commands. Group expansion, AUTHORIZER demotion, archived accounts and concurrent
role updates are separate boundaries.

The government membership journey also promotes MEMBER to AUTHORIZER, then adds
and removes a weaker VIEWER grant. The stored role must remain AUTHORIZER and
both HTTP reads must still return the same exact transaction. Removing AUTHORIZER
must persist MEMBER and preserve reading; removing MEMBER must leave no active
role and deny reading. Explicit persisted-role assertions prevent a failed role
command from passing merely because earlier membership still permits reads.
The bearer, balances and ledger remain unchanged through these transitions.

## Archived government history

The membership lifecycle ends by archiving the funded government account through
its actual command. Archive state and retained MEMBER access are asserted before
the same token reads the exact historical credit from both history and feed.
A separate outsider token receives 403/FORBIDDEN for history and initial/saved
feed cursors. Archive does not erase the credit or move funds, and token IDs and
ledger checkpoint remain unchanged. This follows TransactionService's explicit
archived-history contract; the in-game government commands intentionally have a
different archive-read policy, covered by GovernmentArchiveLifecycleTest.
