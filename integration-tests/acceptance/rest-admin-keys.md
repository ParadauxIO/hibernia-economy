# REST admin key lifecycle assertion review (PAR-499)

Sources: `AdminApiKeyController`, `AdminApiKeyService`, `AuthService` in
`treasury-rest-api`. Routes are relative to `/api/v1/admin/api-keys`.

| Route | Live assertions | Remaining coverage |
| --- | --- | --- |
| POST `/{id}/rotate` | Existing admin tests check JTI/expiry, old-token rejection, revoked-key conflict and auth gates. `AdminKeyLifecycleTest` checks old-token spending/history rejection, unchanged balances/checkpoint, stable key/account/owner identity and recovery through real Paper reissue | Concurrent SERVICE self-rotation, concurrency with transfers/reissue/revoke, expiry boundaries |
| POST `/{id}/revoke` | Existing tests check unknown key and auth gates. New lifecycle checks repeated revocation, spending/history rejection, rotation refusal without JTI mutation, and a separate key still spending successfully | Concurrent SERVICE self-revocation and in-flight work, expiry boundaries |

The new lifecycle starts with a legitimate 1.25 payment. Admin rotation makes
that credential fail both data and money routes with TOKEN_SUPERSEDED. A separate
key transfers 0.25 back. Real `/treasuryapi personal reissue` recovers the same key
ID/account with a new JTI, permitting 7.89; the original token remains invalid.
Repeated admin revocation returns 204 and blocks the replacement credential with
TOKEN_REVOKED. A subsequent rotate returns 409 without changing its JTI. Every
invalid money/history attempt preserves both balances and the transaction
checkpoint. The independent key pays 0.89 back, leaving 9992/10008.

This tests stable ownership. It does not establish revocation on ownership
transfer or the safety of reissuing a former owner's key after reassignment;
those are separate authority contracts. SERVICE provisioning uses the established
fixture, while PERSONAL issue/reissue and HTTP verification are real. See
`results.md` for run evidence; no mutation experiment was performed for this test.

## Business key scope across admin invalidation and Paper reissue

`BusinessKeyLifecycleTest` runs for firm-wide and account-restricted keys. Each
fixture has a primary account with 23.45 and Reserve with 67.89. A legitimate
1.25 Reserve payment precedes force-rotation; both money and history requests
using the old credential must then fail with TOKEN_SUPERSEDED without changing
balances or the transaction checkpoint. Paper reissue must keep the same key,
firm, owner and optional account restriction while changing JTI.

The reissued scoped credential must still refuse primary-account spending and
history. The firm-wide credential can read that sibling's history. Both can pay
7.89 from Reserve. Admin revocation then prevents transfer/history with
TOKEN_REVOKED, and a further rotate must return 409 without altering JTI. Final
balances are primary 23.45, Reserve 58.75, recipient 10009.14, proprietor 9908.66;
default account identity and both active links remain intact. This covers static
firm membership; proprietor changes, account unlinking during reissue and
concurrent operations remain separate gaps.

## SERVICE token rotation and self-invalidation

`ServiceKeyLifecycleTest` starts with a SERVICE credential performing an admin
rename, then rotates it through `/auth/rotate`. The returned token must still
perform admin rename and transfer operations, proving the SERVICE scope survives
JWT reconstruction. The original token must fail rename, transfer and rotation
with TOKEN_SUPERSEDED, without changing the persisted name, money or checkpoint.

Two cases then have the current credential force-rotate or revoke its own key
through the admin API. The initiating request succeeds; subsequent admin rename,
admin transfer and auth rotation fail with the appropriate 401 error. The DB
records changed JTI for force-rotation or the revoked flag with unchanged JTI for
revocation. A separate SERVICE key still renames the account and transfers 11
back after the initial 23.45 payment. Final balances are 9987.55/10012.45, with
one transaction and two postings attributed to the separate service owner for
that return payment. This is sequential self-invalidation, not concurrent or
in-flight-request revocation coverage. SERVICE issuance remains a fixture.
