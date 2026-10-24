# Treasury transaction-command assertion review (PAR-499)

Source: `treasury/src/main/java/io/paradaux/treasury/commands/TransactionsCommand.java`
at `b7dfa4a`. Both registered roots are `transactions` and `txns`. This review
covers its nine annotated routes; other Treasury handlers remain in the review
queue. Each row records actual assertions, not coverage inferred from a test name.

| Route | Live evidence inspected | Remaining acceptance |
| --- | --- | --- |
| `help` | `HelpRoutesTest.transactionsHelpShowsExportUsage` checks the dedicated help title and export usage; `TransactionHistoryPaginationTest` now dispatches help through both aliases (passed in both full runs) | Help permission denial |
| root | `TransactionHistoryPaginationTest`: both aliases, exact newest ten transaction IDs, positive amounts/memos and correct page/footer; no rows after live permission revocation | Mixed debit/credit ordering and malformed display text; audit test separately covers exact negative rendering |
| `<page>` | Same test checks ten/three rows across two pages including starting funds, no duplication/omission, zero-page clamp and empty extreme page | Negative page and populated maximum-page boundary; settlement timestamp exactness |
| `audit <target>` | `TransactionAuditTest`: both aliases, different player's exact debit/starting funds without membership, durable actor/target/method/outcome record and live revocation | Offline existing player and absent personal-account branch; missing-target case now proves resolver rejection without account creation |
| `audit <target> <page>` | Same test: explicit first page and empty second page, no leaked rows, exact page-qualified durable path | Populated multi-page audit and navigation footer |
| `auditaccount <accountId>` | Same test through both aliases: correct personal account despite no membership, durable actor UUID/name/role and target, revoked permission blocks output/new access record | Business/government accounts; missing-ID case proves no successful access log or ledger mutation |
| `auditaccount <accountId> <page>` | Same test checks explicit first page and empty second page with canonical page-qualified path | Populated multi-page account audit |
| `export` | `TransactionExportTest`: both aliases, actual gzip HTTP upload and downloaded CSV, exactly debit/starting-grant rows, IDs/amounts, quoted memo/message and actor; headers; revoked node makes no upload; 503 then successful recovery | Newline/Unicode/formula-like text, all metadata/time fields, 50,000-row cap, malformed host response and interrupted network |
| `export <accountId>` | Same test: explicit own-account CSV equals own export; another player's account is denied even with audit privilege. Government viewer denied, member/authorizer succeed, removal denies; business Employee denied, Manager succeeds, Supervisor demotion denies. Exact missing-ID response and no-upload checks | Group membership, non-default business accounts, archived exports and offline/reconnect role changes |

The audit/export permission distinction is intentional: the audit node allows
staff to read accounts without membership; explicit-account export still requires
membership. The audit journey asserts `explorer_audit` entries with method `CMD`,
actor UUID/name, role `treasury.transactions.audit`, account target, canonical
command path/page and outcome 200. No extra money postings occur during reads.

`DataExportServiceImpl` selects up to 50,000 postings, builds CSV, then releases
DB reads before upload. The runner now points `BytebinServiceImpl` at a local
HTTP capture service; gzip and CSV serialization, upload, returned link and fetch
remain real. A punctuation-bearing memo comes from an actual REST transfer issued
with a real player API key, because chat arguments intentionally sanitize it.
The capture service can return HTTP 503 and retains request evidence. It substitutes
the external host, so hosted Bytebin availability, expiry and one-read enforcement
remain unverified.

See `results.md` for executed clean-pair evidence and fixture corrections. Missing
evidence in this review is not itself a demonstrated production defect.

Shared-account export cases now exercise real membership and business role changes
through both aliases. Government VIEWER cannot export even with the audit node;
MEMBER and AUTHORIZER receive identical CSV; member removal blocks upload.
Business Employee cannot export, promotion through Supervisor to Manager permits
export, and demotion back to Supervisor blocks it immediately. The owner exports
the same CSV. Each successful shared export contains exactly the header and one
23.45 credit, with exact posting/transaction/account IDs, memo and initiator UUID.
Membership/role changes leave money and the original two funding postings unchanged.
The missing-account case asserts `Account not found.` and no HTTP request.
See `results.md` for final repeated-run status.
