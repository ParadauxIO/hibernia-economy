# Historical sale → ledger links (PAR-235)

This is a **manual, one-off operator tool**, outside Flyway and application startup.
It only updates `chestshop_sale.txn_id IS NULL`. It never alters the ledger,
accounts, balances, existing links, or read-layer analytics. Production execution
requires separate authorization; this change does not authorize a production run.

## Matching contract

- Exact, case-sensitive `plugin_system = 'ChestShop'`; exactly two postings,
  one negative and one equally positive, with exact `DECIMAL` amounts.
- The customer resolves to their unique PERSONAL account. Use the recorded
  `shop_account_id`, falling back only to the unique PERSONAL account for
  `shop_owner_uuid_bin`. No display-name guessing, redirects, or current business
  membership inference. Missing historical identity stays unlinked.
- `BUY` means customer pays shop; `SELL` means shop pays customer.
- Every participating transaction must settle within **±2 seconds** of the sale
  by default (configurable 1–5 seconds, inclusive). Input times and DB session
  timezone are UTC; the operator chooses an explicit half-open `[start,end)` range.
- Accept a direct buyer → seller transfer, or a legacy buyer → ChestShop SYSTEM
  → seller pair. The legacy debit must precede the credit in transaction ID and
  settle 0–1 seconds before it. Messages must match exactly after replacing
  `Vault withdraw (` with `Vault deposit (` (same parenthesized suffix), or the
  known `ChestShop withdrawal` / `ChestShop deposit` family. No arbitrary fuzzy
  text matching or nearest-candidate selection.
- **Canonical legacy leg: the seller credit** (SYSTEM → seller). For `SELL`, the
  seller is the customer. Both leg IDs remain in the report. This is a link to a
  primary gross money movement; it neither combines balances nor includes tax.
- Require exactly one candidate, no competing sale for **either** leg, no existing
  claim by another sale, and no other possible partner for either legacy leg
  (including transactions without sale records). Linked sales participate in
  ambiguity checks. A sale with multiple options blocks other sales from taking
  any of its options; no greedy assignment is made.
- Admin shops with no recorded account use the uniquely named `ChestShop` SYSTEM
  account and may match one faucet/sink leg. Recorded admin/server accounts use
  their explicit ID. Free sales, incomplete pairs, unsupported/multi-leg money
  movements, uncertain identities, and ambiguities remain NULL.

The SYSTEM account is identified by exact display name `ChestShop` and type
`SYSTEM`, consistent with `AccountMapper.findSystemAccountId`. Its owner UUID is
not assumed: Treasury can use a shared virtual owner for plugin SYSTEM accounts.

PAR-159's read-only analytics pairing is deliberately separate: its aggregate
estimate is insufficient evidence for persisted sale links. The forward PAR-234
path is already present: `EconomyServiceImpl.settle` stores Treasury's returned
primary transaction ID on `Transaction`; `MarketSyncServiceImpl` forwards it to
`MarketServiceImpl`, then Treasury's sale mapper writes it. Free trades stay NULL.

## Setup and dry-run

Requires Python 3.10+ and MariaDB 10.6+ with the repository schema. Install the
pinned driver in a local virtual environment:

```bash
python3 -m venv .context/par235/venv
.context/par235/venv/bin/pip install -r ops/sale-ledger-backfill/requirements.txt
```

Use a SELECT-only database account for the first run. Supply the password via
`PAR235_DB_PASSWORD` through the approved secret mechanism; never put it in argv,
a report, or shell history. TLS is available through the driver configuration
only if extended for a deployment's requirements; the current CLI is intended
for an approved local connection/tunnel, not a public unencrypted DB endpoint.

```bash
.context/par235/venv/bin/python ops/sale-ledger-backfill/backfill.py \
  --host 127.0.0.1 --port 33235 --database DISPOSABLE_DB --user par235_reader \
  --start 2026-06-01T00:00:00Z --end 2026-06-02T00:00:00Z \
  --batch-seconds 60 --window-seconds 2 --max-rows 10000 \
  --report .context/par235/dry-run.jsonl
```

No `--apply` means `START TRANSACTION READ ONLY` for every batch and no UPDATEs.
For a task-local socket, additionally pass `--unix-socket /path/to/task.sock`;
host and port remain required audit labels. Reports use exclusive creation with mode 0600 so a
rerun cannot overwrite previous evidence. Store reports with restricted access:
they contain player UUIDs, account IDs, and transaction messages, but no password.

## Review, apply, and resume

1. Choose a closed historical interval well behind live traffic and make sure
   historical sale imports, account reassignments, and ledger corrections are
   quiescent for the interval **and its halo**. Online forward sales can continue
   outside that interval. The advisory lock serializes this tool's apply runs;
   it does not coordinate unrelated historical writers. The tool locks sale rows
   and gaps in apply mode, but cannot protect identity/ledger history from an
   unrelated writer. Use a stable snapshot/clone for initial match-rate work.
2. Review the dry-run report's counts and match rate; spot-check identity,
   direction, exact amounts, times, both legacy legs and canonical choice against
   authoritative rows. There is no promised production match percentage. A lower
   rate is preferable to inventing identities. Do not widen matching criteria to
   hit a target without reviewing the evidence and changing the tests.
3. After explicit environment-specific execution authorization, use the same
   range/options with `--apply`, a new report path, and an account granted SELECT
   on the source tables plus UPDATE(`txn_id`) on `chestshop_sale`. Apply recomputes
   matches under its own transaction; it does not blindly consume a stale report.
4. Each time batch commits independently. Reports contain durable `sale` intents,
   then `batch_prepared`, then `batch_committed`. Only a `batch_committed` confirms
   writes. `batch_failed` means the tool attempted rollback; earlier commits
   remain. A lost connection during COMMIT or a process crash after COMMIT can
   leave an uncertain outcome: inspect the database and rerun that same interval.
   An intent by itself is **not** proof of a committed update.
5. Resume at the last confirmed `resume_from` using `--start`, or rerun the full
   range with a new report file. Both are safe: existing links stay untouched and
   still block reuse. The failed batch retries from its start, never from an
   intermediate sale ID. Rows left NULL remain retryable; already linked counts
   are separate from the current run's eligible denominator.

`max_rows` caps each source result set (sales with halo, postings, accounts, and
claims across all lookups); excess aborts the current batch with no committed changes.
Reduce batch size when dense intervals hit the cap. A 2×window sale halo and
3×window ledger halo prevent ambiguity from disappearing at batch/range edges.
Lookups of existing claims use `idx_cs_txn` across **all time**. Queries use the
existing time/posting indexes; no schema/index migration is applied. Inspect
query plans and run off-peak after reviewing a representative authorized clone;
the cap bounds returned data, not the database optimizer's work. Fanout above
1,000 candidate combinations for a sale stops rather than generating an unbounded
report. More than 10,000 candidates across a batch also stops the batch.
Memory/work is bounded per batch, but very dense windows can still be
expensive. Only the operator's explicit time range bounds the total run.

## Report interpretation

JSONL contains a run ID, matching version/options, per-sale evidence and batch
outcomes, then a summary on success. `matched_percent` is matches / previously
NULL sales examined in this run; `already_linked` is excluded. The report retains
sale identity, exact price, direction, candidate IDs, both legacy legs, posting
endpoints, settlement times, and messages.

| Reason | Meaning |
| --- | --- |
| `match` | One candidate passes every check; proposed in dry-run, written only after confirmed apply commit. |
| `no_candidate` | No supported exact identity/amount/time/direction candidate (includes incomplete or unsupported legs). |
| `non_positive_price` | Goods-only/free or invalid price; no positive settlement to attach. |
| `unresolved_identity` | Customer/shop identity is missing, nonunique, or the same account. |
| `ambiguous_candidates` | Multiple direct/pair candidates; no tie-breaker. |
| `competing_sale` | Another sale in the halo could use either leg, even if already linked. |
| `already_claimed` | Another sale anywhere already references a participating transaction. |
| `ambiguous_legacy_pair` | A legacy leg can pair with a different transaction, even without a recorded sale. |

## Disposable verification

Fast matching checks (DB checks explicitly skip without a socket):

```bash
python3 -m unittest discover -s ops/sale-ledger-backfill -v
```

For full verification, start a **new task-owned MariaDB datadir/socket** with
networking disabled; do not use the shared fixture server. The tests create
unique `par235_test_<uuid>` databases, load the real V1/V6 schema including balance
triggers and the sale→ledger FK, and drop only their own databases/users.

```bash
PAR235_TEST_SOCKET=/path/to/task-owned.sock \
  .context/par235/venv/bin/python -m unittest discover -s ops/sale-ledger-backfill -v
```

Coverage includes exact amount/identity/time, BUY/SELL canonical direction,
legacy ordering/messages, competing candidates/sales, linked-sale and global
claim protection, admin/free trades, case-sensitive plugin and multi-leg
rejection, SELECT-only dry-run, idempotent reruns, batch boundaries/row caps, and
an injected database failure after a committed batch followed by resumption.
