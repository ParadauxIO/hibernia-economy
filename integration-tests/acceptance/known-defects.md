# Defects found and repaired through PAR-499 live coverage

The sections below retain the original pre-fix observations and evidence. They
were pre-existing product defects discovered by added live tests, not product
regressions introduced by the coverage commit `33c759d`.

## Current verification status

All twelve defect areas now have production fixes on this branch. The enabled
regressions and selected authorization/custom-item controls passed twice from clean
rebuilt stacks: `20261021T234424Z-1362229/run-{1,2}`, **47 passed each**, zero failures,
errors or skips; 123.11s / 126.12s. Full updated-suite verification remains pending.
The earlier wording below about enabled failures and unchanged production describes
the original discovery runs, not the current branch.

Fixes use overflow-safe offsets; resolved firm IDs for disband authorization;
message-template styling; archived-target rejection; a Business-supplied Treasury
API lookup for active firm defaults; REST Discord/duplicate-name validation;
retained-link recovery during disband; transactional revocation of former-owner
personal keys; and full-width vanilla item codes for custom-item classification.
Overflow tests now also cover 214748366 and 429496731, alongside Integer.MAX_VALUE.
No regressions were disabled or converted into expected failures.

## Balance leaderboard page overflow

- Trigger: `/baltop 2147483647` as a player with `treasury.baltop`.
- Expected: empty out-of-range page without a server error.
- Observed: internal-error reply; MariaDB syntax error 1064 at offset `-20`.
- Cause: `BaltopCommand.showBaltop` multiplies `(page - 1) * 10` in int arithmetic.
- Regression: `BaltopOverflowProbeTest.maximumIntegerPageReturnsEmptyWithoutChangingMoney`.
- Evidence: `build/integration-acceptance/20261021T172428Z-3287147/run-1`.
- Fix verification should also test the first overflow boundary and values that
  wrap to a positive offset, as well as normal/zero/negative pages and ranking.

## Typed payment accepts archived government destinations

- Setup: create a government department, fund it with 23.45, archive it and verify
  the persisted archive flag; use a separate ordinary payer with 10000.
- Triggers: `/paya government <name> 1` and `/paya account <id> 1`.
- Expected: command rejection without funds or postings changing.
- Observed for both: Paid reply, payer 9999, archived account 24.45, paired -1/+1 postings.
- Cause: `AccountResolver` returns the archived government/numeric ID;
  `PayAccountCommand` passes it through without an archive guard. The ledger service
  intentionally leaves that guard to callers, as documented by
  `LedgerServiceBreadthIT.transfer_fromArchivedAccount_stillSucceedsAtServiceLayer`.
  `/pay`, government transfers and REST transfer guards reject archived targets.
- Regression: both parameter cases in `ArchivedTypedPaymentTest`.
- Evidence: `build/integration-acceptance/20261021T174921Z-577206/run-1`.
- Fix verification should preserve authorized admin inspection of archived records,
  reject ordinary payments to archived targets, and cover aliases/other account types.
  Do not change shared resolver semantics blindly: admin read commands also use it.

Focused reproduction uses the standard runner, for example:

```sh
IT_SKIP_BUILD=1 IT_MC_PORT=26565 IT_RCON_PORT=26575 IT_DB_PORT=34306 \
IT_REST_PORT=8180 IT_WEBHOOK_PORT=8199 IT_BYTEBIN_PORT=8198 \
python3 ops/integration/acceptance.py --runs 1 --test '*ArchivedTypedPaymentTest'
```

Replace the filter with `*BaltopOverflowProbeTest` for the other defect. A production
fix requires rebuilding the plugin: `IT_SKIP_BUILD=1` otherwise reuses the old jar.

## Proprietor cannot resume a partially completed disband

- Setup: two funded firm accounts (23.45 and 67.89); temporarily reject a debit
  from either account using an isolated SQL trigger, then disband as proprietor.
- Expected first result: archived firm, one failed account retained, other account
  drained; this is correctly observed, including rollback of the failed transfer.
- Trigger: remove the fault and follow the message's `/firm disband <name>` retry.
- Expected: proprietor can confirm and recover the retained balance once.
- Observed: `You do not have permission to run this command.`; retained funds stay
  linked to the archived firm. Both failure positions reproduce it.
- Cause: command-level `firms.isProprietor(String, UUID)` calls
  `FirmMapper.isProprietorByFirmName`, whose SQL excludes `is_archived = 1`. The
  underlying disband service uses the archived-inclusive ID-based check, so the
  command rejects the owner before the resumable service path is reached.
- Regression: both `PartialDisbandRecoveryTest.proprietorCanResumeAfterDatabaseRecovers`
  cases remain enabled and failing.
- Evidence: `build/integration-acceptance/20261021T182159Z-2387013/run-1`: two
  proprietor failures and two successful administrator recoveries after fault removal.
  Initial evidence `20261021T181924Z-2294749/run-1` also showed owner retry denial
  while the fault remained.
- The authorized admin disband route can recover the money; its test checks no
  duplicate payout to the already-drained account. This is not a proprietor fix.
- A fix must preserve ownership checks and reject non-proprietors; avoid changing
  active-only lookups globally when the recovery route needs an archived-inclusive
  check. No production fix has been made.

### The same proprietor check rejects numeric firm references

`NumericFirmDisbandTest` separately tests a numeric prompt and a numeric
confirmation after a valid name-based prompt. Both return the no-permission
message for the actual proprietor of an active firm. `getAnyFirmByNameOrId`
resolves the numeric token, but the subsequent String ownership check compares
that raw token against `display_name` instead of using the resolved firm ID.

Evidence: `build/integration-acceptance/20261021T182744Z-2755779/run-1`: two numeric
regression failures and two administrator rollback/recovery passes. The numeric
confirmation leaves firm/link active, funds at 23.45, owner 9976.55, and creates
no payout posting or transaction record. These two active regressions bring the
known failing case count to seven across three defect areas. The source explicitly
resolves names or IDs; no production behavior was changed to force a pass.

## Business leaderboard page overflow

- Trigger: `/business baltop 2147483647` with `business.baltop` permission.
- Expected: empty distant page with no ledger change.
- Observed: internal-error reply; server logs `IndexOutOfBoundsException:
  fromIndex = -20` from `FirmTransactionServiceImpl.getFirmBalanceTop`. Personal
  balance and transaction checkpoint remain unchanged.
- Cause: int `(page - 1) * pageSize` wraps negative, then becomes the start index
  passed to `ranked.subList`. This is separate code from the Treasury SQL-offset
  overflow, though both arise from unchecked page arithmetic.
- Regression: `FirmBalanceOverflowTest.maximumIntegerPageReturnsEmptyWithoutChangingLedger`.
- Evidence: `build/integration-acceptance/20261021T183436Z-2995891/run-1`.
- The same run's five ranking cases failed in setup because the fixture tried
  to create two firms for one proprietor inside the real 300-second cooldown.
  Ranking fixtures were changed to distinct proprietors; the overflow regression
  remains enabled and failing. Eight failing regression cases are now retained.

## Defunct firm list exposes formatting markup

- Trigger: create a firm, disband it, then run the proprietor's `/business list`
  (also reproduced through `firm`, `company`, `db`, and `democracybusiness`).
- Expected client text: `1. <firm name> owned by <owner> (Defunct)` with styling
  represented as component formatting, not visible markup.
- Observed: literal `<strikethrough>` / `</strikethrough>` around the name and
  `<red>` / `</red>` around `Defunct` in the received component's plain text.
- `FirmCommands.sendFirms` embeds tags in String placeholder values. The mock
  client uses Adventure's plain-text serializer on received components, so these
  are actual text contents rather than serialized style attributes.
- Regression: all five aliases of `FirmReadCommandsTest.listsTrackEmploymentAndDistinguishDefunctOwnedFromGlobalFirms`.
- Evidence: `build/integration-acceptance/20261021T184239Z-3464247/run-1` and
  `20261021T184539Z-3618185/run-1`: each 12 passes and five rendering failures.
  In the latter, the rendering assertion runs last: employment, firing, global
  exclusion of archived firms, permission revocation, unchanged balances and
  unchanged transaction checkpoint all pass before it.
- No stripping of tags or acceptance of broken output was added to the tests.
  Thirteen active failing cases now cover five defect areas. No production fix
  has been made; click-target behavior of the malformed row is not yet verified.

## Business global-list page overflow

- Trigger: `/business list all 2147483647` with `business.list.all` permission.
- Expected: empty distant page, no financial effects.
- Observed: internal-error reply, MariaDB 1064 syntax error near `-20`.
- Cause: `FirmServiceImpl.listAllFirms` calculates `(page - 1) * pageSize` using
  int arithmetic, then passes the wrapped offset to `listAllFiltered`. This is
  a separate handler/service path from the Business aggregate leaderboard.
- Regression: `FirmListOverflowTest.maximumIntegerPageReturnsEmptyWithoutChangingLedger`.
- Evidence: `build/integration-acceptance/20261021T185311Z-77077/run-1`: one failure;
  only the empty-page assertion fails. Personal balance stays 10000 and the
  transaction checkpoint stays unchanged (all checked with grouped assertions).
- Normal populated pages and lower-page clamping passed twice separately. The
  overflow case remains active; fourteen failing cases now cover six defect
  areas. No production change has been made.

## Reused firm name routes payment to retained account of old firm

- Setup: fund firm A with 23.45, inject a scoped debit failure, and admin-disband
  it. A is archived but its undrained Treasury account remains active and linked.
  Release A's name and let another proprietor create firm B with that same name.
- Trigger: `/paya business <reused-name> 11 ReplacementFunds`.
- Expected: B receives 11; A retains 23.45; payer ends at 9989.
- Observed: success reply and payer 9989, but B remains zero and A rises to 34.45.
  This is a wrong-destination transfer, not merely missing output or failed setup.
- Cause: `AccountResolver` uses `resolveBusinessAccountByToken`, falling back to
  `<name> Corporate Account`. `AccountMapper.findBusinessAccountByName` selects an
  active Treasury account by that display name with `LIMIT 1`, without resolving
  the active firm/default account. Both old retained and new accounts qualify.
- Regression: `ReleasedNameRecoveryTest.paymentByReusedNameMustReachReplacementWhileOldFirmRetainsFunds`.
- Evidence: initial `build/integration-acceptance/20261021T192158Z-1773933/run-1`
  found the replacement stayed zero. `20261021T192419Z-1843670/run-1` explicitly
  confirms both wrong destination balances with grouped assertions; the payer
  assertion passes. The separate numeric-ID recovery case passes in that run.
- The existing release policy allows this state; its policy semantics are
  characterized, not endorsed as a requirement. A correction must prevent
  ambiguous monetary routing while preserving recovery of retained funds.
  Numeric-account funding is used only in the independent recovery test, not to
  weaken or skip this payment regression. Fifteen failing cases now cover seven
  defect areas. No production fix has been made.

## REST firm profile bypasses Discord invite validation

- Trigger: use a real whole-firm BUSINESS token to PATCH `/api/v1/firms/me` with
  `discordUrl` set to `javascript:alert(1)`, `https://discord.gg/ab<red>bad`, or
  `https://example.com/abc`, after storing a valid Discord invite.
- Expected: reject with 400 and preserve the valid stored invite, matching the
  strict Discord invite contract enforced by the Business command/service path.
- Observed: all three return 200, echo the invalid value, and persist it in
  `firm.discord_url`. Grouped ledger/checkpoint assertions pass.
- Cause: REST `FirmService.updateFirm` invokes `FirmFieldLimits.validate`, which
  checks column lengths only, then writes directly through its mapper. It does
  not enforce the invite scheme/host/code constraints used by Business.
- Regression: three cases of `FirmProfileTest.invalidDiscordInviteCannotReplaceStoredProfile`.
- Evidence: `build/integration-acceptance/20261021T192942Z-2132346/run-1`:
  three validation failures; normal patch semantics and account-scoped transfers
  pass. These are stored-validation failures; browser execution, exploitability,
  and behavior of other rendering consumers have not been demonstrated.
- Eighteen active failing cases now cover eight defect areas. No production fix
  has been made, and the invalid inputs are not treated as valid characterization.

## REST account rename bypasses same-firm name uniqueness

- Setup: create a firm with its default account and a second account named Reserve.
- Trigger: PATCH `/firms/me/accounts/<reserveId>/display-name` with the default
  account's existing display name, using a player-issued whole-firm BUSINESS key.
- Expected: a client-error response and Reserve unchanged. Business account
  creation explicitly rejects same-firm duplicate names because they make
  name-based deposit/withdraw references ambiguous.
- Observed: 200 and the duplicate persisted on Reserve; the default account keeps
  the same name. The transaction checkpoint stays unchanged.
- Cause: REST `FirmService.updateAccountDisplayName` checks token scope, nonblank
  input and length, then directly updates `accounts.display_name` without checking
  sibling accounts. This bypasses the uniqueness guard in Business account creation.
- Regression: `FirmAccountPresentationTest.duplicateDisplayNameCannotMakeFirmAccountsAmbiguous`.
- Evidence: `build/integration-acceptance/20261021T193436Z-2416900/run-1`: one
  duplicate-name failure, two passing account scope/presentation journeys.
- The duplicate state is demonstrated; downstream misrouting and concurrency
  outcomes are not tested by this case. Nineteen active failing cases now track
  nine defect areas. No production fix has been made.

## REST disband cannot recover partial Paper disband

- Setup: fund a firm account with 23.45, reject its debit with the scoped SQL
  trigger, and invoke Paper's administrator disband. The firm archives but its
  undrained account remains linked and funded. Remove the fault before retrying.
- Trigger: POST `/admin/firms/<id>/disband` with a valid SERVICE key.
- Expected recovery: refund retained funds once and finish account archive/unlink.
- Observed: 200 with `archived: true` and an empty `accounts` list, while the link
  remains active, balance stays 23.45, proprietor stays 9976.55 and no payout
  posting or transaction is created.
- Cause: `AdminFirmService.disband` treats any archived firm as fully completed
  before inspecting links. Its single-transaction assumption holds for its own
  work but not for the shared DB state produced by Paper's partial-drain workflow.
- Regression: `PaperDisbandRecoveryTest.restRetryOfPartialPaperDisbandRecoversRetainedAccount`.
- Evidence: `build/integration-acceptance/20261021T195506Z-3556814/run-1`: one
  partial-recovery failure, completed-Paper no-op control and two API atomicity
  cases pass. Seven grouped assertions establish the incomplete recovery state.
- The existing Paper admin retry can recover retained funds; that does not fix
  REST recovery. Twenty active failing cases now cover ten defect areas. No
  production change was made and the incomplete state is not accepted as correct.

## Personal API key retains access after account ownership changes

- Setup: issue a real PERSONAL key, prove transfer/history access, then use SERVICE
  PATCH `/admin/accounts/<id>/owner` to reassign that personal account to a new
  UUID with no existing personal account. Both response and DB lookup confirm
  the new owner; the former owner no longer resolves to that account.
- Trigger: reuse the former owner's existing key for transfer or history.
- Expected: 401/403 and no financial effects after ownership has moved.
- Observed: transfer returns 200 and moves 7.89 from the reassigned account:
  9998.75 → 9990.86, recipient 10001.25 → 10009.14, new ledger transaction.
  History independently returns 200 with the account's posting data.
- Cause: `AdminAccountService.changeOwner` updates the account owner without
  revoking/rebinding keys. `JwtTokenVerifier` checks the acc claim against the
  key record, but not current account ownership; `TransferService` trusts that
  claim as the PERSONAL source, and `TransactionService` similarly treats the
  matching claim as owner access.
- Regression: both modes of `PersonalOwnershipKeyTest.formerOwnersPersonalKeyCannotAccessReassignedAccount`.
- Evidence: `build/integration-acceptance/20261021T200913Z-133883/run-1`: two
  failures, with the new-owner first-join control and existing owner-change guard
  passing. Grouped assertions confirm the actual unauthorized money movement.
- These tests do not cover key rotation after transfer, other account types, or
  ownership changes through another surface. Twenty-two active failing cases
  now cover eleven defect areas. No production fix has been made.

## Long vanilla item names are classified as custom

- Trigger: create a normal WHITE_GLAZED_TERRACOTTA business shop with ordinary
  Minecraft items, purchase twice, then read the public item directory.
- Expected: `itemCustom=false` for this unmodified vanilla material.
- Observed: `itemCustom=true`; the live regression remains enabled.
- Cause: `MarketServiceImpl.isCustom` compares `items.getName(item, 0)` (full
  canonical name) against `itemCodes.encode(item, MAXIMUM_SIGN_WIDTH)` (shortened
  vanilla sign name). `ItemCodeServiceImpl.encode` shortens long material names
  even without custom item metadata, so inequality does not establish customness.
- Regression: `MarketItemPaginationTest.tradedItemsRankByCountThenNameAcrossPopulatedAndEmptyPages`.
  The custom-flag assertion runs after independent ranking/page/search/money checks.
- Initial evidence: `build/integration-acceptance/20261021T214734Z-2430032/run-1`.
  This first run stopped at the custom flag before completing all pagination checks.
- Production is unchanged. Fix verification should distinguish shortened vanilla
  names from genuine custom resolvers, aliases and metadata-bearing item codes.
- Follow-up evidence: `build/integration-acceptance/20261021T214935Z-2518020/run-1`.
  Ranking, every pagination/search check and exact money/inventory assertions
  completed before the final vanilla/custom assertion failed again.
