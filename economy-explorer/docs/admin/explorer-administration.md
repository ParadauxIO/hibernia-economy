---
title: Explorer administration
order: 5
description: Operate player investigations, account and firm controls, and audit records with explicit verification.
---

# Explorer administration

Open [Admin](/admin) from the top bar (on a phone, from the menu). Tools appear
in the sidebar according to individual permissions; a role name alone does not
authorize an action. See [roles](/docs/admin/roles).

The admin dashboard shows what needs attention: failing and auto-disabled
webhooks, overdue webhook deliveries (deliveries to government webhooks,
held while government API keys are switched off, are counted separately), API keys expiring within 14 days, and
requests refused in the last 24 hours. Below that are the latest admin changes
from the audit log, each plugin's latest ledger transaction, and the state of
every feature flag. Each panel needs the permission of the tool it summarises,
and its figures refresh about once a minute.
Financial mutations require the [configured Treasury admin API](/docs/admin/config-services).

## Players, transactions and investigations

**Players** requires `player.read.all`; account histories still obey account-read
permissions. Search for the Minecraft name/UUID and inspect the relevant account,
firm and date window. **Transactions** requires `transactions.read` and shows the
global ledger, newest first. Narrow it with filter chips: settled date, category
(Taxes, or the recording plugin's category), plugin, account, initiator or
authorizer, amount, and the type of the paying or receiving account. Type `#123`
to find one transaction, or text to match messages. Select a row to see its
postings in a panel without leaving the list. **Follow the money** in that panel
lists what each receiving account did in the 1, 6, 24 or 72 hours either side.

Every filter reads an index. Amount searches at most 31 days, and the account-type
and message filters at most 7; without a **Settled** filter they cover the most
recent days, and a longer Settled range is cut to its last days. A note under the
filters says when that happened. The count stops at "1,000+": use **Older**,
**Newer** and **Jump to** a date to move through the list. The **Sales** feed
(`market.sales.read`) works the same way for ChestShop trades, filtered by item,
customer, shop owner, firm, direction, price and date (price and item-name text
cover at most 7 days).

**Export → All results** downloads up to 100,000 rows of what the filters select,
as CSV or JSON, with the same permission as the page. Each export is written to the
audit log with action **Export**, the dataset as the target, and the filters in the path.
Record transaction IDs, exact amounts and UTC times, rather than screenshots alone.
Public balances do not imply access to private transaction histories.

## Account changes

[Admin → Accounts](/admin/accounts) requires `account.admin.read`. Search by
account ID, name or owner, and verify the type, owner, archived state and balance.
Names can be ambiguous: use numeric IDs for a correction.

| Action | Permission | Procedure and verification |
|---|---|---|
| Move money | `account.transfer` | Enter source/destination IDs, decimal amount and explanatory memo/task reference. Submit once. Record the returned transaction ID, inspect both postings and verify balances. |
| Rename | `account.rename` | Save the old name; set the new name, reload and verify the ID is unchanged. Avoid renaming bootstrapped/config-referenced accounts. |
| Change owner | `account.owner.change` | Enter a UUID or known player name; verify the resulting owner. A PERSONAL account cannot move to someone who already has one. This does not transfer a Business firm; use its ownership workflow. |
| Archive/unarchive | `account.archive` | Record the balance and dependents first. Archiving hides/disables the account; it does not sweep or refund funds. Reload to verify the state. Unarchive the same ID if archiving was mistaken, then check dependent shops/integrations. |

The backend retains account history. Do not assume the generic archive tool checks
all application relationships or requires a zero balance: settle funds and check
firm links, aliases, payroll, tax destinations, API keys and shops first. Use firm
disband for retiring a whole firm. Keep the primitive `Eco`, starting-balance,
tax and fines accounts available.

If a transfer times out, retry with identical details in the same browser tab.
The form retains its retry key across reloads until success; Treasury returns
the original result instead of moving money twice. Changing the details starts a
new transfer. If you close the tab, inspect the ledger before submitting again. For an incorrect completed transfer, make one documented
compensating transfer and verify both records. Do not delete the original.

## Firm changes

[Admin → Firms](/admin/firms) requires `firm.admin.read`. Open the firm's detail
page and confirm its ID, proprietor and linked accounts before changing it.

| Action | Permission | Behavior |
|---|---|---|
| Rename | `firm.rename` | Enforces format and case-insensitive name reservation. The REST service does not read Business's configurable blocked-term list; check the proposed name against server policy too. |
| Update HQ/Discord | `firm.details.update` | Save the previous values; update and reload to confirm. |
| Disband | `firm.disband` | Retype the exact firm name. REST sweeps positive balances to the proprietor's personal account, archives accounts, removes live links and archives the firm in one DB transaction. |

Before disbanding, capture all linked IDs and balances and confirm the proprietor's
personal account exists. If positive funds have no destination, REST refuses with
`PROPRIETOR_NO_PERSONAL_ACCOUNT`. Resolve the ownership/account problem first.
The firm report retains former account links with their removal times after disband.
Its balances and archive states show current values, not historical snapshots.
After success, inspect the returned per-account sweep amounts, destination,
transactions and archive states. Investigate negative balances separately; a
positive-balance sweep does not settle debt. There is no Explorer undo-disband
button: restoring the business involves more than unarchiving its accounts.

The firm report's **Operator controls** provides:

- **Change proprietor** (`firm.owner.change`): retype the firm name and enter the
  incoming player name or UUID. Treasury updates the firm and every live linked
  account, ends both proprietors' employee memberships, cancels pending
  transfers, reconciles member/authorizer access, and revokes the outgoing
  proprietor's business keys for this firm in one transaction. Independent
  viewer and LuckPerms group grants remain; inspect those separately.
- **Reconcile account access** (`firm.accounts.sync`): repair linked account
  ownership and role-derived access. Proprietor/ADMIN gets AUTHORIZER; FINANCIAL
  gets MEMBER. Stale member/authorizer grants are removed; independent viewer
  and group grants remain.
- **Balance-tax exemption** (`firm.tax.update`): set or clear the same exemption
  used by `/business tax exempt`. The current state appears above the controls.
- **Release reserved name** (`firm.name.release`): available for a disbanded firm.
  Retype its name. Treasury refuses while any live account links remain. The
  historical firm keeps its ID and name; a new firm can reserve that name.

Reload the report and inspect account ownership/access and the audit log after
an intervention. Use the firm's numeric ID once its name has been reused.
These controls do not restore a disbanded business or change in-game chat state;
see the [Business reference](/docs/reference/business) for in-game administration.

## Audit evidence

[Admin → Audit log](/admin/audit) requires `audit.read` (seeded into the admin
role). It lists `explorer_audit` newest first. Filter it with chips:

- **Actor**: a player name or UUID.
- **Action**: what happened, for example `Account: rename` or `Group: change roles`.
- **Target type** and **Target id**: an account number, firm id, group id, role key or player UUID.
- **Time**: a preset or a custom range.

Each row has **Details**. It shows the request facts (time, actor, request path,
source IP, sign-in subject) and, for admin changes, a field-by-field comparison
of the values before and after the change. Every account, firm, API key, rate
limit, webhook, group and role change made through the Explorer records them.
Rows written before this release have no before or after values.

Two kinds of read are recorded too:

- privileged views of other players' data, as before;
- **relationship reads**: a government or business account opened through
  ownership, account access, an LP group grant or firm finance access, rather
  than a global `.all` permission. These are recorded at most once per person,
  account and hour, so refreshing a page does not flood the log.

The log is fail-open: if a row can't be written the page still works, and the
failure is counted in the `explorer_audit_writes_total{outcome="error"}` metric.
Watch that metric; a log that silently stops writing looks the same as a quiet one.

Treasury admin calls also name the acting player. An admin transfer records the
admin who made it as the ledger initiator (it used to record the owner of the
Explorer's service key), and the REST API writes one `AdminRequests` log line per
admin call with the acting player's UUID.

Correlate the audit log with REST/plugin logs and ledger transaction IDs. An
audit record is not a substitute for checking the actual ledger result, and
logged role names are not a historical snapshot of every permission. Preserve
only the evidence needed for the incident; do not paste tokens or webhook URLs
into tasks.

## Other consoles

- [Roles and Groups](/docs/admin/roles): direct grants, manual/LP membership and delegation.
- [API keys and issuer limits](/docs/admin/access-and-credentials#api-keys): revoke, rotate and limits.
- [Webhooks](/docs/admin/webhooks): subscriptions, failures and recovery.
