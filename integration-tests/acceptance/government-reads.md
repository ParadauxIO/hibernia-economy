# Government read and viewer routes (PAR-499)

Source: `treasury/src/main/java/io/paradaux/treasury/commands/GovCommand.java`.
Both registered roots (`gov`, `government`) are exercised. This review covers
these eight read/viewer routes and the role-transition journeys below, not all government commands.

| Route | Assertions | Remaining gaps |
| --- | --- | --- |
| `account balance <name>` | Exact 12.78 direct-viewer and 23.45 group-viewer balances; denied after account or LP membership removal; balances unchanged by reads | Group-role downgrade, archived/missing accounts, console and custom currency format; individual role/permission transitions are covered below |
| `account history <name>` | Exact newest ten of twelve credits, IDs/amounts/memos, header/account/page/footer; group viewer sees its exact single credit; removed viewers denied | Mixed debit/credit, archived/missing accounts; authorizer permission revocation is covered below |
| `account history <name> <page>` | Exact remaining two credits and no next-page footer, zero clamp equals first page, maximum int returns empty | Negative page, cap boundary, populated audit-size data |
| `account viewer add <account> <player>` | Actual grant enables direct reader; admin list contains exact player; viewer does not acquire spend rights | Unknown/offline target, unauthorized manager, idempotence and interaction with higher roles |
| `account viewer remove <account> <player>` | Both read routes immediately denied; list becomes empty after final grant removal | Higher-role preservation, repeated removal, unauthorized manager |
| `account viewer addgroup <account> <group>` | Mixed-case name resolves real LP group; its member can read but not spend or list viewers; admin list retains entered spelling | Unknown/later-created group, nested/contextual groups, unauthorized manager |
| `account viewer removegroup <account> <group>` | Revokes both read routes without reconnect; direct viewer remains in list; no money moves | Repeated removal, higher-role preservation, unauthorized manager |
| `account viewer list <account>` | Exact direct-player and group entries, group removal leaves direct entry, final removal gives empty message; viewer itself is denied | Member and authorizer listing, offline name display, global permission revocation |

`GovernmentHistoryAccessTest` funds the department through twelve actual `/paya`
commands. It checks 24 paired funding postings remain unchanged, department 12.78,
funder 9987.22, reader 10000, and a stable transaction checkpoint during reads and
denied spending. `GovernmentGroupViewerTest` checks actual LuckPerms parent removal
and restoration separately from removing the department's group grant. Its
23.45 seed remains intact; balances and transaction checkpoint stay unchanged.

Group names are matched case-insensitively for access while the list preserves
the spelling supplied to the add command. The latter is a characterization of
the mapper/renderer, not a requirement that group display names be lowercase.
Execution evidence and fixture corrections are in `results.md`.

## Individual role transitions and command permissions

`GovernmentRoleTransitionTest` runs both aliases. Direct SQL assertions read only
the tested account/UUID's active access level, alongside real command outcomes.

| Trigger | Expected and asserted behavior | Remaining boundaries |
| --- | --- | --- |
| `account member add`, then `account auth add` | Active MEMBER becomes AUTHORIZER; can add a viewer and pay 7 | Group promotion and exact authorizer lists |
| Viewer/member grants and viewer removal against AUTHORIZER | Stronger role remains AUTHORIZER and management works | Concurrent grants and repeated soft-delete/reactivation |
| `account auth remove` | Active role becomes MEMBER; viewer removal is denied and target stays VIEWER; payout of 3 succeeds | Group demotion and global bypass interactions |
| Viewer removal against MEMBER | MEMBER remains active | Group equivalent |
| `account member remove` against MEMBER | No active access; payout and balance read denied | Member removal directly against AUTHORIZER and group equivalent |
| Member removal against pure VIEWER | VIEWER remains active and reads exact 90 balance | Group equivalent |
| Revoke `treasury.gov.account.view` while AUTHORIZER remains | Balance and history denied; restoring node restores exact read | Viewers/members and contextual LP nodes |
| Revoke `treasury.gov.account.manage` while AUTHORIZER remains | Viewer grant denied, no target access row; restoring node enables grant | Other management routes and group role |
| Revoke root `treasury.gov` while AUTHORIZER remains | No history data and payout does not move money; role stays AUTHORIZER | Root help, console sender and contextual permissions |

The successful transition payouts create exactly four postings (-7/+7/-3/+3),
with department 90, recipient 10010, actor 10000 and funder 9900. Permission-only
checks leave the 23.45 seed intact, all personal balances unchanged and no new
transaction IDs. These scenarios demonstrate that access level and command
permission are distinct gates. They do not certify every government route merely
because the routes share helpers. See `results.md` for executed evidence.

## Funded archive/list lifecycle

`GovernmentArchiveLifecycleTest` exercises both aliases with two funded
departments (23.45 and 11), checking exact list rows and name ordering. After
archiving the first, its row disappears but its original account ID, balance and
posting remain. Government balance/history, transfers in/out and payout reject
it; `/pay` rejects it too. Creating the same name is denied without reactivation.
The entire read/rejection/archive sequence leaves the transaction checkpoint and
all three balances unchanged. Dedicated typed-payment checks originally found a bypass; it is now fixed, with
`ArchivedTypedPaymentTest` asserting rejection and unchanged funds/postings. See
`known-defects.md` for the retained pre-fix evidence. The remaining archive branches
in the review table still require coverage.
