# Account resolution and collision audit (PAR-144)

Operators should use account IDs for government money. A player's name is not
proof that their personal wallet belongs to a same-named government. This audit
covers the repository at `8710bb34` plus the PAR-144 changes described here.

## Resolution policy

Explicit account IDs and explicit PERSONAL operations retain their stated type.
An untyped name that matches both a known player and an active GOVERNMENT account
must be refused unless the integration has a defined government routing rule.
Never recover from an account lookup failure by paying another account type.
Name comparisons use the database's existing case-insensitive collation; this
change introduces no new normalization or global name reservation.

The existing integration rules remain:

- Vault uses an explicit `account_redirects` UUID mapping first. Without one,
  a colliding current player name refuses reads, creation and money movement:
  balance is zero, existence/funds checks are false, and transfers return failure.
  Both String and OfflinePlayer overloads are covered. The ledger checks again
  before its personal-account cache, including direct Vault-service callers.
  Redirected deposits skip the PERSONAL-only source-income-tax API, which would
  otherwise debit the operator's personal wallet for income credited elsewhere.
- ChestShop's legacy owner-UUID rule selects the sole active GOVERNMENT account
  owned by that UUID. Multiple active government accounts are ambiguous and fail.
  With none, personal fallback requires no player/government name collision.
  A failed government lookup fails the operation. Synthetic business UUIDs retain
  their explicit account ID. A name match alone never establishes ownership.
- Treasury `/pay` and `/gov payout` refuse player/government name collisions.
  REST by-player-name reads and transfers return `409 AMBIGUOUS_NAME`; explicit
  player UUID queries target PERSONAL accounts, and account-ID transfers retain
  their chosen ID.

Deploy Treasury (including its API) and ChestShop together: ChestShop calls the
new `TreasuryApi.hasGovernmentNameCollision(UUID)` method. The collision check
reads the current directory rather than caching a negative result across player
renames. This adds a database lookup to legacy player-wallet resolution.

## Resolver inventory

| Surface | Evidence and disposition |
| --- | --- |
| `/pay`, `/pay-account` | `PayCommand` rejects a colliding name; account-ID form is explicit. `PayTargetResolver` supplies suggestions, not money routing. |
| `/gov payout`, government transfers | `GovCommand` guards the player-name recipient; government arguments resolve as GOVERNMENT. |
| REST by-player and player transfers | `AccountService.resolvePlayerAccount`, `TransferService.transferToPlayer` preserve existing name guards and explicit UUID behavior; `LegacyNameCollisionGuardTest` covers both. |
| Vault read/create/deposit/withdraw | `VaultEconomyAdapter` and `LedgerServiceImpl.resolveVaultAccountId`: redirect first, otherwise refuse current-name collisions before personal fallback. |
| ChestShop signs, balances and settlement | `AccountServiceImpl` identifies the sign owner; `EconomyServiceImpl.resolveGovernmentAccountId` now refuses multiple wallets, collisions without a government owner match, and lookup failures. Business aliases use `BusinessAccountServiceImpl` and explicit account IDs. |
| Vault source-income tax | `VaultEconomyAdapter.deposit` skips `TaxApi.applySourceIncomeTax` for redirected deposits; the tax API resolves PERSONAL only and must not charge a wallet that did not receive this deposit. |
| Native Treasury API | `getAccountByUUID`, `getBalanceByOwnerUuid`, `resolveOrCreatePersonal` are PERSONAL contracts; type-and-owner lookups return a list. Explicit account-ID transfer does not infer another wallet from its owner. |
| Player balance, `/eco`, personal fines, salaries, taxes, transaction audit | Explicit player/personal operations; government fine debtors and account audits have separate account-ID forms. These must not be rerouted because an owner also has government membership or a matching name. |
| Business accounts, firm transfers and Explorer | Firm/default-account operations are BUSINESS scoped; Explorer account links and admin edits carry IDs, player lookup resolves identity, and directory/search results do not silently select a payment wallet. |
| Realty | [PartyWallets at 7439f88f](https://github.com/MCCitiesNetwork/realty/blob/7439f88f2043d8e484307f2e09a850c4de96440a/realty-paper/src/main/java/io/github/md5sha256/realty/economy/PartyWallets.java) distinguishes `Party.Personal` from account/group parties with account IDs and stored kinds. Personal parties intentionally remain personal even for government operators. Property assignment and deployed-version verification remain part of remediation; this checkout has no populated Realty submodule. |

## Recurring check and source visibility

Treasury schedules `AccountResolutionAuditTask` asynchronously one tick after
startup and every 24 hours (server ticks) thereafter. Each run logs its collision
count and each government ID, player UUID, active personal ID (if any), and Vault
redirect ID (if any). A query failure logs an error and retries at the next run;
it is not reported as a clean audit. Bukkit cancels plugin tasks on disable.

Run [ops/account-name-collisions.sql](../ops/account-name-collisions.sql) with an
operator's read-only SQL connection for an immediate inventory. It uses
`economy_players`, includes players with no personal wallet, and excludes archived
governments and placeholder player names. An archived personal wallet is omitted
from this active-routing report; include archived history in remediation review.

`GovServiceImpl.createDepartmentAccount` already rejects names belonging to known
players. The daily audit also exposes the reverse order (player joins or renames
after government creation), generic API/admin account edits and concurrent
creation. It reports collisions without blocking player login or moving funds.
Global cross-kind name uniqueness was withdrawn under PAR-501 on 2026-10-25;
V40/V41 remain retired. This work does not restore that feature. ADT-193 is Done
and is no longer a release blocker for this audit.

## Read-only inventory — 2026-11-08

The connected `s80037_economy` database returned four active collisions at
02:01:18 UTC. This was an unbounded collision query over 147,356 accounts and
140,162 directory entries (counts observed at 02:00:20 UTC). Ledger balances below
were read in subsequent queries during the same audit, not one locked snapshot.

| Current player name | GOVERNMENT | Active PERSONAL | Vault redirect | Personal ledger balance |
| --- | --- | --- | --- | --- |
| GovInterior | 3400 | 116480 | 3400 | 300.00 |
| GovReserve | 10316 | 124651 | 10316 | 300.00 |
| DCGovernmentOR | 27107 | 104236 | None | 25,204.37 |
| DCGovernmentAV | 49929 | 104237 | None | 3,523.67 |

OR and AV require an explicit Vault-routing decision before rollout of the refusal
guard: their currently unredirected Vault operations will fail. The two configured
redirects remain usable, but do not establish that historical personal-wallet funds
have been reconciled. The AG/DH/DOJ watch-list personal wallets each had a zero
ledger balance. No records or balances were changed.

Attribution matters: OR's personal wallet has 517,271.52 in `rest-api` credits,
81,357.56 in `PostmanPat` credits, and 630,840.00 in `Treasury-Core` debits. AV has
27,526.00 in `rest-api` credits and 20,557.08 in `Treasury-Core` debits. These are
selected lifetime posting aggregates, not a complete correction calculation.
The current `realty` aggregates are OR credits/debits 1,505.00 / 13,818.67 and
AV 30.00 / 20,496.25. They do not substantiate treating the ticket's historical
549,082 figure as Realty credits or as money currently owed.

A latest-ten sample of OR/AV transactions with `Early Lease Termination Refund`
in the transaction message contained **debits**, including OR transaction
`10462218` (2026-11-07 17:50:08, -25.00, `or-apt-50`) and `10434783`
(2026-11-06 18:22:05, -200.00, `or-c106`). This proves recent Realty activity still
uses the personal wallet, but does not by itself establish the intended payer.
The PAR-143 follow-up is to verify those properties' stored party/account IDs
and their intended debit/credit routing against the deployed Realty version.
Do not infer posting direction or ownership from a refund memo alone.

Connector note: passing a database argument to the MariaDB MCP tool returned an
empty first result set for its generated `USE …; SELECT …`. The observations above
come from single SELECT statements with fully qualified table names, checked
against a nonempty count query. The empty connector results were not counted as
evidence of no collisions.

## Historical remediation inventory

The following entries preserve the June 2026 ticket evidence; use the dated
inventory above for the newer observed balances.
A collision establishes routing risk, not that every credit is misdirected.

| Name | GOVERNMENT | PERSONAL | Review |
| --- | --- | --- | --- |
| DCGovernmentAV | 49929 | 104237 | Attribute historical credits/debits and verify current property parties and redirects. |
| DCGovernmentOR | 27107 | 104236 | PAR-143 reported $549,082 cumulative credits and $17,172 remaining, including early lease termination refunds. Neither number is an approved transfer amount. Player UUID: `a3521f89-b5d0-4860-a0a0-5467f82e3233`. |
| DCGovernmentAG / DCgovernmentDH / DCGovernmentDOJ | No match reported | 79295 / 77276 / 49046 | Watch list; no collision was established by the ticket. |
| DCGovernment / DCGovernmentJUD | 2 / 99393 | No alt reported | Verify against the current inventory before assuming no collision. |

For each confirmed collision, the operator responsible for the government and
Realty configuration should:

1. Capture a dated read-only inventory, account metadata, balances, redirects,
   property party assignments, and relevant ledger transactions/postings. Include
   archived accounts and any renamed player identities in the historical review.
2. Classify credits and subsequent debits by transaction ID, source plugin, memo,
   property and intended beneficiary. Separate cumulative receipts from current
   balance and account for earlier corrections. Record disputed items explicitly.
3. Configure the intended future receiving account: a typed government/account
   party in Realty, or an explicitly reviewed Vault redirect. Never infer a
   redirect solely from equal names. Verify the installed integration supports
   this policy and refresh/restart Treasury's redirect cache after any separately
   authorized mapping change.
4. Prepare a reviewed correction list with source/destination IDs, exact amount,
   original transaction IDs, reason and stable idempotency key for each correction.
   Obtain operator approval for those concrete financial changes. Do not sweep an
   entire personal wallet or transfer the ticket's historical totals automatically.
5. Execute approved compensating transfers through Treasury's ledger API, retaining
   the original history. Verify postings sum to zero, balances change by exactly
   the approved amount, and a representative new payout reaches the configured
   government account. Retain correction transaction IDs with PAR-143/PAR-144.

This implementation does not deploy, alter production routing, or move historical
funds. Closure of operational remediation requires property/transaction attribution,
a refreshed snapshot at execution time, and approval of the correction list.

## Verification — 2026-11-08

The final logic passed a focused 107-test run:

```bash
bash gradlew :treasury:spotlessApply :treasury:test \
  --tests '*AccountResolutionAuditIT' --tests '*AccountResolutionAuditTaskTest' \
  --tests '*VaultEconomyAdapterTest' --tests '*LedgerServiceTransferIT' -Pci=true
```

This covers real MariaDB collision/redirect reads, case-insensitive names,
rename/archival changes, players without personal wallets, rejection before a
cached personal lookup, preserved personal/government balances, explicit redirect
credits, no personal income tax on redirected deposits, and startup/daily async
scheduling with recovery after query failure. ChestShop's full suite passed
1,863 tests, including the new refusal and archived-candidate cases. REST's
`AccountServiceIT` (16 tests) and `LegacyNameCollisionGuardTest` (3) passed.
The live guard query's EXPLAIN used the player UUID primary key and
`idx_accounts_type_name`, each with an estimated one row for the sampled OR UUID.
Observed balances above are sums of ledger postings, not approved corrections.

`bash gradlew build --continue -Pci=true` completed its remaining checks but
**failed overall**: Treasury passed 842/843 tests and REST passed 356/357.
`TaxApiCollectIT.collectBatch_processesEachIndependently_preservesOrder` and
`AdminFirmOperationsIT.releaseAndTaxChangesEnforceFirmStateAndServiceScope`
failed during fixture reset with MariaDB lock-wait timeouts; REST named
`TRUNCATE TABLE accounts`. The Treasury failure preceded the new audit fixture.
An earlier full run hit the same reset error in a different existing ledger test;
that entire 54-test class subsequently passed both focused and full runs.
The lock-timeout cause is not fully isolated; no assertions, test selection in the
full build, or timeout settings were relaxed to obtain a pass.

Business (907 tests), ChestShop (1,863), the API plugin (151), and the published
Treasury API (24) passed. No production transfer, redirect mutation, deployment,
or live Minecraft journey was executed by this work. The recurring check becomes
active only when the changed Treasury plugin is deployed.
