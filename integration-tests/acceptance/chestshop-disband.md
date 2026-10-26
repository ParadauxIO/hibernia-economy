# Existing shop after firm disband (PAR-499)

`ArchivedBusinessShopTest` creates a business shop through the diagnostic creation
event and proves a real customer purchase succeeds: two dirt for seven, leaving
22 stock. The owner then performs the actual two-step funded disband. The firm
is archived, its active account links and default account disappear, its account
is drained to zero and the owner receives seven.

A second real customer click must leave the buyer at 9993, former owner at 10007,
archived destination at zero, buyer inventory two, chest stock 22, recorded sales
one and the ledger checkpoint unchanged. Soft assertions collect all effects if
any rejection invariant fails. The positive purchase before disband guards against
a fixture that never traded in the first place.

Observed rejection is the existing shop-access message, not an explicit archived
account error. `BusinessAccountServiceImpl.canAccessBusinessAccount` deliberately
allows access when no live firm owns the account (PAR-29); trading with an accessible
shop is rejected by the transaction path. Although native account-ID resolution
lacks the archived guard used by alias resolution, this experiment does not show
an accepted payment into the archived account. No production change is justified
by that source observation alone.

Coverage does not certify public-directory removal, container recovery permissions,
alias/name-bound legacy signs or all possible configuration combinations.
The sell-side and access-setting cases below extend the initial buy-side evidence.
Public buyable filtering currently means a configured buy price, not a guarantee
that every attempted transaction will succeed; archival listing policy needs its
own explicit contract before asserting directory exclusion.

## Access setting enabled and disabled

The disband test is parameterized over `ignoreAccessPerms=true/false`, reads the
actual runtime configuration back before setup and restores true after each case.
Both modes reject the second purchase with identical money/item/ledger invariants.
This does not establish a low-level archived-account guard: for business signs,
`AccountServiceImpl.isOwner` delegates to account access, which already returns
true when no live firm owns the account. The owner predicate therefore retains
the access rejection even when the optional access-permissions predicate is off.
No separate privileged trading bypass was identified in this interaction path.

## Customer sells after disband

A second parameterized case funds the firm with 100 and proves a real sale of two
dirt for seven succeeds, leaving the seller with two more dirt and balance 10007,
the firm with 93 and the owner with 9900. The actual funded disband refunds 93 to
the owner, reaching 9993, and removes the firm's active account links.

A second left-click must retain the seller's remaining two items, chest stock two,
seller balance 10007, owner balance 9993, destination zero, one recorded sale and
unchanged ledger checkpoint. Both access configurations are exercised and read
back. The direct sign fixture does not cover buy-shop creation. This checks the
sell interaction's observable rejection without attributing it solely to archive
validation: the drained balance and business-access rules are also present.

## Former-proprietor stock recovery

`DisbandStockRecoveryTest` creates a stocked business shop through the diagnostic
creation event. An outsider's real container-open packet must not open the active
firm's chest. After the real two-step disband, the former proprietor opens the
chest without op elevation and quick-moves all 36 dirt through actual container
packets. Server inventory must show chest zero and proprietor 36. Closing the
container must refresh the existing shop's tracked stock to zero with its ID
preserved. Both players' balances stay 10000, the archived account stays zero,
no sales are recorded and the post-disband ledger checkpoint remains unchanged.

This verifies recovery by the former proprietor and ordinary pre-disband outsider
protection. It does not assert exclusive former-owner access after disband: the
existing PAR-29 rule deliberately makes orphaned business accounts accessible.
The fixture uses an unfunded firm, avoiding any claim about refund behavior here;
funded refund/trade rejection is covered by the neighboring cases.
