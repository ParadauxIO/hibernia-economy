# REST firm route assertion review (PAR-499)

Source: `treasury-rest-api/src/main/java/io/paradaux/treasuryrestapi/controller/FirmController.java`. Paths below are
relative to `/api/v1`. This is a partial assertion review, not REST acceptance.

| Route | Inspected live evidence | Remaining acceptance |
| --- | --- | --- |
| GET `/firms/me` | `FirmProfileTest`: exact ID/name/null-or-string metadata/archived flag, initial and final state with player-issued whole-firm key | Archived firm/token interaction and renamed/reused identities |
| PATCH `/firms/me` | Exact response and DB fields; whitespace trimming, omitted and explicit-null preservation, blank clearing, HQ length 64/65, Discord overlength, empty body rejection, atomic rejection when another field is valid; PERSONAL/account-scoped/anonymous denied without mutation | **Known defect:** invalid Discord schemes/host/markup persist; duplicate JSON fields, malformed types, archived state and concurrent updates remain |
| GET `/firms/{firmName}` | Authenticated unrelated PERSONAL caller sees exact ID/name/metadata/default account, nonempty creation time | Exact timestamp, missing/archived/reused name behavior, public-field privacy and anonymous policy |
| GET `/firms/{firmName}/balance` | `FirmBalanceLifecycleTest`: exact ID/name/decimal totals 34.45 → 23.45 → 0.00 through real archive/disband; whole/scoped/unrelated PERSONAL readers, case-insensitive name, anonymous 401 and missing 404 | Negative totals, concurrent reads/writes, reused names and archived-but-still-linked accounts |
| GET `/firms/me/accounts` | `FirmAccountPresentationTest`: exact ordered two-account rows, IDs/names/type/decimal balances/archive flag; whole key sees both, scoped key sees only Reserve, foreign key sees only own firm; PERSONAL/anonymous denied | Real archive removes Reserve from whole/scoped lists; disband produces an empty list in `FirmBalanceLifecycleTest`. Malformed-token and archived-but-linked account cases remain |
| GET `/firms/me/employees` | `FirmStaffReadTest`: exact UUID/name/role/join timestamp, two hires, promotion ordering, each firing and final empty list; foreign-firm isolation and scoped/PERSONAL/anonymous denial | Missing player records, disconnected/renamed players, rehire timestamps, equal-rank tie order and archived firms |
| GET `/firms/me/roles` | `FirmStaffReadTest`: exact five default names/ranks/permission sets, custom-role create, permission add/remove, rename, soft-delete exclusion; foreign isolation and key restrictions | Semantics of proprietorLike/defaultRole flags (type only checked), reordered ranks, permission ordering and archived firms |
| PATCH `/firms/me/accounts/{accountId}/display-name` | Exact response/DB/list readback; scoped own write succeeds, scoped sibling/foreign/PERSONAL/anonymous denied; trim, 255/256 boundary, missing/null/blank bodies; immutable funds and links | **Known defect:** duplicate same-firm name accepted. Real archived/unlinked target denies whole/scoped rename in `FirmBalanceLifecycleTest`; missing IDs, duplicate races and downstream labels remain |

The profile fixture has a real 23.45-funded account: patch/read/rejection operations
leave that balance, proprietor 9976.55, unrelated reader 10000 and transaction
checkpoint unchanged. A valid initial URL is stored before invalid update tests,
so rejection must preserve an existing value rather than merely leave an empty
field. The invalid URL regressions remain enabled. Public response checks do not
yet assert the complete field set or exact creation timestamp.

Tokens come from the actual Paper commands and requests reach the running REST
service sharing the live server's MariaDB. No HTTP or persistence mock is used.
The REST route's length-only validation differs from the command's strict invite
validation; see `known-defects.md` and execution details in `results.md`.

`FirmAccountPresentationTest` funds default/Reserve with 23.45/11 and creates a
separate foreign firm. Exact ordered lists exclude the foreign account; its own
key returns only that firm's row. Renames preserve all five returned fields except
the expected name, and list readback matches. Whole-firm and account-scoped keys
both perform authorized writes. Denials verify persisted names after each request;
invalid bodies preserve the previously accepted 255-character value. Balances,
default pointer, active link count and transaction checkpoint remain unchanged.

A separate active regression proves the REST rename accepts a duplicate sibling
name, bypassing the Business creation guard. No downstream transaction behavior
is inferred from that stored duplicate alone. The REST's explicit 255-character
limit is tested as its own contract, not as proof of compatibility with every
in-game account-name consumer.

`FirmStaffReadTest` drives real command mutations while an existing whole-firm
HTTP token reads results. Two accepted hires appear with exact identity and stored
join timestamps. Promotion places the Supervisor ahead of the Employee; each
firing removes only its row. Role responses track a new DEFAULT role, added then
removed FINANCIAL permission, rename and deletion, returning to the baseline five
roles. A foreign firm's role/employee lists remain unchanged. Account-scoped,
PERSONAL and unauthenticated requests cannot read these firm-wide lists.

The role boolean fields are checked for type, not assigned semantic expectations;
that distinction is an explicit remaining gap. Join timestamp equality uses the
stored DB timestamp as an independent serialization check, not a wall-clock guess.
All four players retain 10000, the default account stays zero, the active link
count stays one and the transaction checkpoint is unchanged.

`FirmBalanceLifecycleTest` uses the actual account-archive command after funding
default/Reserve with 23.45/11. The public total falls from 34.45 to 23.45; the
whole-key list contains only the default account and the Reserve-scoped list is
empty. Both keys are forbidden from renaming the archived/unlinked Reserve. The
retained 11 and all funding postings remain unchanged. This characterizes the
current archive policy rather than asserting that archiving refunds money.

Disband then refunds only the still-linked 23.45, leaving owner 9989 and the
previously archived account holding 11. Public profile remains readable as archived
with a null default pointer; the total is 0.00 and the account list is empty.
Historical links/postings remain. Read and rejection phases never advance the
transaction checkpoint. Existing `SoftDeleteAuthTest` additionally isolates removal
of the link without changing the account's archive flag, using SQL fixture setup.

## Administrator disband transaction

`AdminDisbandAtomicityTest` covers POST `/admin/firms/{firmId}/disband`, implemented
by `AdminFirmController`/`AdminFirmService`. Its contract differs from the Paper
partial-drain command: the REST operation uses one database transaction.

Two cases reject the debit on either the first or second funded account via the
scoped `LedgerFaults` trigger. The 500 response leaves firm/accounts unarchived,
default pointer intact, all three links active, owner at 9908.66 and funded balances
23.45/67.89 unchanged. No payout postings or orphan transaction headers survive,
including when the first account was processed before the second debit failed.

Removing the fault and retrying produces exact per-account receipts: 23.45 and
67.89 to the original proprietor's personal account, and null amount/destination
for the empty account. All three accounts and the firm archive; links are soft
removed and the default pointer cleared. Four exact postings in two transactions
restore the owner to 10000, leaving the separate service-key operator at 10000.
A repeat returns an archived no-op receipt and produces no new transaction.

The SERVICE key is minted through the existing fixture helper with a real key row
and signed JWT, then traverses real HTTP authentication; service-key provisioning
is not under test. Faults are permitted only on the disposable runner's loopback
DB and are removed on scope exit. Forced disband/credit interleavings, process crash,
negative balances remain separate acceptance gaps. Recovery of an already
partially archived Paper firm has a demonstrated defect, described below.

`AdminDisbandMissingProprietorTest` covers the no-personal-account branch using a
SQL fixture assignment to the known offline UUID of a player who has not joined.
This models imported/legacy state; it does not test proprietor-transfer side
effects. The actual account creation, HTTP disband and subsequent join remain real.

- With two empty accounts, disband succeeds with null sweep/destination receipts,
  archives/unlinks both, and creates neither a personal account nor a transaction.
- With an empty first account and 23.45 in the second, disband returns 422
  `PROPRIETOR_NO_PERSONAL_ACCOUNT`. The earlier tentative archive/link removal
  rolls back, both accounts and the firm remain active, default is unchanged,
  all funds/postings remain and no personal account or transaction is created.
- A real first join creates exactly one personal account through the normal
  starting-balance flow. Retrying pays that proprietor 23.45 via one transaction
  and two exact postings, ending at 10023.45; the original funder/service operator
  remains at 9976.55. Final archives, default clearing and soft-removal history
  are checked. The fixture never repairs the missing account directly in SQL.

## REST retry after Paper disband

`PaperDisbandRecoveryTest` creates completed and partial states through the actual
Paper admin command, not hand-written archive flags. Completed Paper disband is
an idempotent REST no-op: 200, archived receipt, no processed accounts, no extra
postings or refund.

The partial state exposes a defect: after the debit fault is removed, REST still
returns that same no-op response because the firm row is archived. Its active
link, 23.45 retained balance and owner's 9976.55 remain unchanged, with no payout.
The failing regression stays enabled; API atomicity cannot establish recovery
from another writer's valid partial state. See `known-defects.md`. Paper admin
recovery has separate passing evidence and is not evidence that REST recovered.

## Concurrent administrator disband requests

`ConcurrentAdminDisbandTest` releases eight HTTP callers from one start barrier
against the same two-account firm. Each response must succeed and identify the
archived firm. Across all receipts, exactly one 23.45 and one 67.89 payout is
reported, both to the original proprietor. Final ledger checks require four exact
postings in two transaction records, zero source balances, proprietor/operator
10000 each, cleared default pointer, zero active links and two historical links.

Grouped status/state assertions still check conservation if a request returns an
error. Futures and the launch barrier have bounded waits; no failed request is
retried to obtain a pass. This is actual concurrent client-call coverage, not a
forced server-side lock interleaving or proof over all schedules. Concurrent
credits, process termination and deterministic lock-order experiments remain open.
