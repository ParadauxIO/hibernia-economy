# Business firm lifecycle assertion review (PAR-499)

Source: `business/src/main/java/io/paradaux/business/commands/FirmCommands.java`.
The five roots are `business`, `firm`, `company`, `db`, `democracybusiness`.
This is a partial route review, not complete acceptance of the handler.

| Route | Evidence inspected | Remaining acceptance |
| --- | --- | --- |
| `create <firm>` | Existing `FirmCommandsTest` verifies persisted firm/proprietor. `FundedDisbandTest` creates through all five aliases and obtains the linked default account | Exact default roles/access, creation broadcast, permission revocation and failure rollback |
| `disband <firm>` | New funded test through all five aliases: exact aggregate 91.34 prompt, firm remains active, two linked accounts retain 23.45/67.89, transaction checkpoint unchanged | Replacement-prompt behavior and reconnect semantics; caller/firm/permission isolation and real-time expiry are covered below |
| `disband <firm> confirm` | No pending request rejects with no mutation. Confirm drains both accounts exactly once to owner, archives firm/accounts, clears default pointer and soft-removes both links. Repeat confirms report already disbanded and create no postings | Concurrent confirms, partial failure/resume and disconnect/restart during drain; owner changes and unauthorized callers are covered below |

The original test named `disbandRemovesFirmAndReturnsFundsToProprietor` used an
unfunded default account and asserted only the firm archive flag. The new test
checks the actual monetary contract: owner 9908.66 before confirmation and 10000
afterward; exactly four postings (-23.45/+23.45/-67.89/+67.89) in two transactions;
each source ends at zero with its funding and drain posting retained.

The assertion helper `firmAccountCount` counts all historical links. Added
`activeFirmAccountCount` explicitly filters `removed_at IS NULL`; disband must
leave zero active links and preserve two historical rows. The source mapper and
migration define soft removal, so expecting physical deletion would be incorrect.

The original list and info checks were weaker; the additional coverage below
checks their actual contents and state transitions. Attributes and admin
operations still need a fuller assertion review. CarbonChat integration remains
unavailable on this disposable stack. Execution evidence is in `results.md`.

## Confirmation authorization and proprietor changes

`DisbandAuthorizationTest` uses all five aliases and ordinary players holding only
the disband node (plus spam exemption). An independent admin creates and funds
two firms, then assigns them to the tested owner through the real admin command.

- A non-proprietor cannot prompt or confirm another player's pending disband.
- Prompting firm A does not authorize confirming firm B. Both funded accounts
  stay active and linked, with balances 23.45/67.89 and no new transaction IDs.
- Revoking `business.disband` blocks confirmation while the prompt is pending.
- Reassigning firm A after its prompt causes the old proprietor's confirmation
  to fail. The new proprietor must prompt independently; pending authority does
  not transfer between players.
- The new proprietor's own prompt/confirm pays exactly 23.45 to that player via
  two postings. The previous proprietor retains 10000, the independent funder
  retains 9908.66, and firm B keeps 67.89 with its link active.

This also exercises `admin set proprietor` through each alias and verifies the
persisted proprietor plus its effect on disband authority/refund destination. It
does not certify all ownership-transfer side effects or the admin permission gate.
Concurrent requests and recovery after a partially completed drain remain
open; no timing manipulation or production-code change was used.

## Real-time confirmation expiry

`DisbandExpiryTest` uses the ordinary proprietor's real pending confirmation and
the production 60-second lifetime. It waits 61 monotonic seconds after receiving
the prompt, then confirms once through a different alias. The expired response
must leave the firm active, its default account linked with 23.45, proprietor at
9976.55, and the transaction checkpoint unchanged. A fresh prompt through
`company`, confirmed through `db`, succeeds and returns exactly 23.45 through
two postings. No server clock replacement, shortened TTL or confirmation retry is
used. This deliberately adds about one minute to a full core run; all five alias
routes are otherwise covered by the faster funded/authorization cases. Exact
expiry-boundary timing, clock jumps and reconnect behavior remain separate gaps.

## Partial drain rollback and recovery

`PartialDisbandRecoveryTest` injects a database debit failure for either of two
isolated fixture accounts, using `LedgerFaults.rejectDebits`. The fault is a
scoped MariaDB BEFORE INSERT trigger raising SQLSTATE 45000; normal production
command, service and transaction paths still execute. It is automatically dropped
on scope exit and only enabled under the disposable runner with a loopback DB.

The initial disband reports exactly one undrained account. The firm is archived
and its default pointer cleared; the failed account stays unarchived/linked with
its full balance and just its original funding posting. The other account becomes
zero/archived/unlinked with its funding and payout postings. Owner balance is
10000 minus the retained amount, proving the failed account's tentative credit
was rolled back. Both account positions are tested.

Administrator retries under the fault leave the two successful postings unchanged.
After removing the fault, the admin route drains only the retained balance: owner
10000, two zero/archived/unlinked accounts, exactly two completed transactions and
four postings (-23.45/+23.45/-67.89/+67.89).

**Proprietor recovery is defective:** its retry is denied after the firm becomes
archived, even when the DB fault has been removed. The command's name-based owner
lookup excludes archived firms, unlike the service's ID-based lookup. Two failing
regressions remain enabled. See `known-defects.md` and `results.md`; do not describe
partial-disband recovery as generally accepted from the admin success alone.
Process crash recovery and forced concurrent sweep interleavings remain unverified.

The rollback assertions now also count transaction records independently of
postings, requiring exactly one successful record after a failed/repeated partial
drain and two after completed recovery. This detects otherwise invisible orphan
transaction records. `NumericFirmDisbandTest` found a second manifestation of the
String ownership lookup defect: numeric prompt/confirmation resolves the firm
but then denies its actual proprietor. Both cases remain failing; see the defect
report. Numeric firm references are not accepted by this review.

## Aggregate firm leaderboard

`FirmBalanceRankingTest` uses all five roots. It reads only the pre-fixture maximum
to place two independently owned firms above existing fixtures, then funds them
through actual commands: A default M+20/reserve 80 totals M+100; B default M+70/
reserve 10 totals M+80. Thus using only defaults reverses the required ranking.
Exact names, ranks and formatted aggregate amounts are asserted. Default/explicit
first page, zero and negative page produce identical rows; a distant safe page
is empty. Disbanding A removes it and promotes B to rank one. Revoking the read
node blocks both default and explicit-page routes. Ledger checkpoints and exact
personal balances demonstrate that reading does not move money.

Distinct proprietors preserve the real 300-second creation cooldown instead of
changing configuration. Populated second-page boundaries, ties, zero/negative
firm totals and unlinked/non-archived account combinations remain open. The
separate maximum-int page test found an actual negative-index overflow; see
`known-defects.md`. The general leaderboard is therefore not fully accepted.

## Firm information and employment lists

`FirmReadCommandsTest` exercises all five aliases as ordinary readers with explicit
permissions. Information includes exact ID, proprietor, aggregate $34.45 from two
accounts, initial N/A metadata, changed HQ and Discord URL, identical numeric-ID
lookup, and archived status with zero remaining balance and preserved metadata.
Revoking `business.info` hides those fields. Reads preserve the transaction
checkpoint; disband returns the funded 34.45 to the proprietor.

The list journey verifies exact owner/member rows, an unrelated player's empty
list, real offer acceptance and firing, other-player lists, newest-first global
listing and explicit page one. Disband leaves the firm in the proprietor's list
but excludes it globally. Revoking other-player/global permissions blocks rows;
all fixture balances stay 10000 and no transactions are created.

All independent list assertions pass, but the final defunct-row assertion fails
through every alias: styling tags are visible text. These regressions remain
enabled; see `known-defects.md`. Missing proprietor records, click targets and
additional metadata validation remain unverified.

`FirmListPaginationTest` creates twenty firms through real commands with distinct
proprietors, respecting creation cooldown, then disconnects all those owners.
Across all five aliases, it checks exact first and second pages (ten rows each),
newest-first IDs/names/stored proprietor names, page-local ordinals, page headers,
implicit/explicit first-page equality, zero/negative clamping and a distant empty
page. Fixtures are newer than pre-existing firms, so both full pages have exact
expected contents without depending on another test's data. Revocation denies
default and explicit page routes; reads leave balances and transactions unchanged.
This verifies offline owners with stored player records, not the separate missing
player-record UUID fallback or clickable component behavior.

Maximum-int list pagination is defective: `FirmListOverflowTest` receives an
internal error after the int offset wraps to -20 and MariaDB rejects the query.
Its no-financial-effect assertions pass; the empty-page regression stays enabled.
See `known-defects.md` for evidence and the separate leaderboard overflow.

## Attribute authorization and Discord validation

`FirmAttributeAuthorizationTest` exercises ordinary HQ/Discord setters and admin
overrides through all five roots. An ordinary proprietor with the matching server
nodes sets exact metadata using a numeric firm ID. An unrelated player holding
those same ordinary nodes is denied by firm-role checks, with both stored fields
unchanged after each attempt. Granting only `business.admin.attribute` lets that
outsider update both fields through the override routes; revoking it blocks both
routes. Revoking the proprietor's ordinary nodes also blocks both setters despite
ownership. Exact metadata remains intact after every denial; owner, zero firm
balance, personal balances and transaction checkpoint are unchanged.

Seven invalid-invite cases run through both ordinary and admin setters: insecure
scheme, wrong host, javascript scheme, embedded MiniMessage tag, a second URL,
one-character code and 33-character code. Each must return `Invalid URL.` and
preserve a previously accepted URL. Positive cases use 2- and 32-character codes,
including mixed-case letters and digits, so rejection checks cannot pass merely
because the setters never work. The command framework's server-permission denial
is asserted separately from the Business handler's role-denial response.

HQ checks cover authorization and persistence with no region provider installed.
Realty-backed valid/invalid plots, archived
targets and click events on the Discord link remain separate gaps. Invalid-input
checks demonstrate the real validation path; no production mutant was executed.

`FirmAttributeRoleTransitionTest` covers promotion/demotion through all five
aliases with the employee connected throughout. Real hire/accept and promotions
take Employee through Supervisor to Manager. Manager's FINANCIAL permission must
not permit either metadata setter. Promotion to Co-Proprietor grants ADMIN and
permits exact HQ/Discord updates using numeric firm references. Demotion back to
Manager immediately denies both setters while their LuckPerms nodes remain
granted. Both stored fields are checked after each rejection, and final proprietor,
zero firm balance, personal balances and transaction checkpoint remain unchanged.
This catches stale role authorization and confusion between FINANCIAL and ADMIN;
cross-firm role propagation remains separate.

`FirmRolePermissionPropagationTest` edits ADMIN on the existing Employee role,
without promotion or reconnect, through all five aliases. The default role denies
both metadata setters. Adding ADMIN enables numeric-ID HQ/Discord writes;
removing it denies both immediately and preserves the last successful values.
The role stays Employee and the server nodes remain granted throughout. Active
role-permission counts follow 1 → 2 → 1.

Two real firm accounts (default and Reserve) independently check propagation:
member/authorizer counts follow 1 → 2 → 1, the proprietor always retains both,
and the exact employee gains then loses both. These assertions detect wrong-user
grants and synchronization limited to the default account. Accounts stay at zero,
personal balances stay 10000 and the transaction checkpoint does not advance.
No manual account sync or configuration override is used. Multi-holder roles,
FINANCIAL-only permission edits, replayed grants and failure rollback remain gaps.

## Funded firm rename

`FirmRenameLifecycleTest` exercises `admin rename` through all five aliases using
an ordinary nonmember with an explicit server permission. Denial before the grant
and after revocation preserves the name. A numeric-ID rename changes the name
while retaining the original ID; the old name stops resolving. A no-op rename and
a case-only rename succeed. `BusinessLedger.firmDisplayName` reads the exact stored
value so case-insensitive SQL lookup cannot conceal failure to change casing.

A second independently owned firm supplies a case-insensitive collision target.
That rename is rejected without changing either name. A digit-prefixed name is
also rejected. Two funded accounts retain their exact IDs, default pointer, active
links and balances (23.45/11); proprietor, HQ, Discord, personal balances and
transaction checkpoint are unchanged throughout the rename journey.

Archived rename targets, concurrent collisions, blocked-name policy, and downstream account display labels remain
separate acceptance gaps. The test does not claim that every consumer updates its
cached display name merely because the firm record changed.

## Disbanded name reservation, release and reuse

`FirmNameReleaseTest` exercises all five aliases. A funded active firm rejects
release and retains its name reservation and 23.45 balance. Disband archives it
and refunds the proprietor; a different player still cannot create the reserved
name. Revoking `business.admin.releasename` blocks release without changing either
flag. An explicit grant allows release, and a second release reports already
released. The archived record retains its original name and identity.

Another proprietor then creates the same name with a distinct firm ID and distinct
default account. Name-based info finds the new firm, while numeric info still
finds the defunct record. A real 7.89 payment by business name reaches only the new
account. The original account stays zero with its two funding/refund postings;
the original proprietor's complete posting snapshot is unchanged. Historical
links remain on the old firm, active links belong to the new one, and exact
balances distinguish the two proprietors. Release/recreation themselves generate
no ledger transactions.

Console sender, nonexistent IDs and concurrent release/create remain separate
gaps. Partial-drain interaction is reviewed below. No production changes
or cooldown overrides were needed; reuse uses a distinct proprietor.

## Numeric recovery after a partial drain and name reuse

`ReleasedNameRecoveryTest` uses a scoped real database debit failure to leave
23.45 on the old firm's active Treasury account while its firm row is archived.
It characterizes the existing policy allowing name release in this state, then
creates a new firm with the same name. With the replacement funded by exact
account ID, removing the fault and admin-disbanding the old numeric firm ID refunds
only the original owner: one transaction with exact -23.45/+23.45 postings. A
repeat numeric disband is a no-op. The replacement retains its active identity,
default account, 11 balance, proprietor and complete posting history. Old/new
archive flags are checked by numeric ID, avoiding ambiguous name-based assertions.

**Named payment is defective in this state:** the paired regression instead funds
by `/paya business <name>` and proves the old retained account receives the 11,
while the replacement stays zero. See `known-defects.md`. Numeric recovery does
not establish safe name-based routing or justify the current partial-release
policy. No production changes were made.
