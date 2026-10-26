# REST account administration assertion review (PAR-499)

Source: `treasury-rest-api/src/main/java/io/paradaux/treasuryrestapi/controller/AdminAccountController.java`.
Paths below are relative to `/api/v1/admin/accounts`. This is partial acceptance.

| Route | Inspected live evidence | Remaining acceptance |
| --- | --- | --- |
| PATCH `/{id}/display-name` | `AdminAccountArchiveTest`: trim and exact five-field response/DB identity; existing `AdminApiTest` covers oversized name and unknown archive target | Missing/blank fields on this route, type-specific name restrictions, duplicate-name effects and archived-account rename |
| PATCH `/{id}/owner` | Existing `AdminApiTest`: government UUID reassignment and PERSONAL duplicate-account guard; `PersonalOwnershipKeyTest`: new-owner first join reuses funded account without reseeding | **Known defect:** former PERSONAL key retains history/spend access. `AccountOwnerResolutionTest` covers name/UUID resolution, repeated self-assignment and rejected inputs; rotation/reissue and archived personal collisions remain |
| POST `/{id}/archive` | Exact funded PERSONAL identity/archive state, repeated call, non-SERVICE denial; archived source and destination transfers both 404 without monetary changes | BUSINESS/GOVERNMENT transfer effects, join while personal is archived, concurrent transfer/archive |
| POST `/{id}/unarchive` | Exact identity restored, repeat call, non-SERVICE denial; original personal tokens transfer both directions after restore | Restoring older personal account when another exists, concurrent calls and archived API-key behavior |

`AdminAccountArchiveTest` renames the account, verifies all five returned fields
and persisted name/owner/archive state, then archives it twice. Both outgoing and
incoming 7.89 attempts return ACCOUNT_NOT_FOUND, leaving both balances 10000 and
the transaction checkpoint unchanged. Unauthorized archive/unarchive requests
leave state intact. Two unarchive calls preserve the same ID and owner.

The same original tokens then transfer 23.45 out and 11 back. Exact receipts,
four postings and two transaction records account for final balances 9987.55 and
10012.45. Archive/recovery do not recreate the account or rotate credentials.
DB UUID values are parsed as UUIDs because SQL text casing is not an identity
property. Service-key provisioning remains fixture setup; real HTTP auth and the
shared ledger are used. See `results.md` for execution evidence.

## Personal ownership transfer and token authority

`PersonalOwnershipKeyTest` reassigns a real funded PERSONAL account through the
admin owner endpoint. A positive control assigns it to a never-joined player's
known offline UUID, then connects that player: the original account ID and 10000
balance are reused, with no second starting-grant transaction. A newly issued key
for the new owner transfers 23.45 successfully, producing two postings and one
transaction; the original owner no longer resolves to that personal account.

Two active regressions expose retained authority of the old key. After reassignment,
that PERSONAL key still reads history with 200 and can transfer 7.89 from the
new owner's account. The tests establish prior legitimate key use, actual changed
ownership, and exact balance/checkpoint changes rather than inferring failure from
status alone. See `known-defects.md`. Correct new-owner access does not prove old
credentials were revoked. Name-based reassignment, rotation/reissue behavior,
other account types and archived-owner collisions remain gaps.

## Owner identifier resolution and rejected writes

`AccountOwnerResolutionTest` moves a funded PERSONAL account to a fresh UUID,
then returns it to its original owner using a whitespace-padded known player
name. Same-owner requests by name and UUID must preserve account identity.
A different player with an existing PERSONAL account must be rejected by either
identifier with 409 PERSONAL_ACCOUNT_EXISTS. Missing/null/blank owner fields,
unknown names, unknown account IDs, and unauthorized requests have explicit
error assertions. After every rejected write, both account owners, types,
archive flags, balances (10000 each), personal-account lookup IDs and transaction
checkpoint must remain unchanged. This covers identifier resolution and rejection
atomicity, not old-key revocation, renamed player records or archived collisions.
Execution evidence is recorded in `results.md`.
