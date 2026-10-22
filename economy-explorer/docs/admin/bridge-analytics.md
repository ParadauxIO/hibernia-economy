---
title: Vault bridge analytics
description: Understand estimated bridge volume, preserved faucets and sinks, query bounds, and audit limitations.
order: 9
---

# Vault bridge analytics estimates (PAR-159)

The dashboard volume/counts, period comparisons (and any velocity derived from
that volume), stats volume, and money-flow aggregate estimate adjacent Vault
bridge legs as a single economic event. The ledger, transaction history, raw REST
feed, account history and sale identities are unchanged. SYSTEM hiding in
counterparty/richest widgets was already shipped separately.

## Classification evidence

The PAR-159 discussion dated 2026-10-17 reports 3.78M historical adjacent pairs,
including 3.39M ChestShop pairs. It also reports unmatched claim-block sinks,
reward faucets, delayed BountyHunters escrow and unequal Essentials fee loops.
These are task evidence, not a new production measurement by this change.

For each SYSTEM debit settling inside the reporting window, inspect the
immediately previous posting by posting ID on that same SYSTEM account. Estimate
a bridge only when:

- The predecessor is an equal positive credit, settled 0–1 seconds earlier.
- Plugin names are present and byte-identical; messages are byte-identical after
  mapping `Vault withdraw (p)` to `Vault deposit (p)`.
- Both transactions contain exactly two balanced postings, and their other
  accounts are non-SYSTEM.

There is no plugin-wide exemption. Unmatched legs remain faucets/sinks in money
flow and volume. Multi-posting transactions remain raw in volume and excluded
from money-flow's two-posting contract. Atomic direct transfers remain unchanged.
Intervening postings, differing amounts/messages/plugins, different clearing
accounts, reversed settlement times and delays over one second prevent matching.

The incoming transaction's volume/count is removed; the outgoing transaction
retains the event and its settlement date. Money-flow replaces both legs with the
inferred source/destination account types, then excludes same-type flows as usual.
A pair crossing a day/period boundary belongs to the outgoing leg. The window
includes one second before the reporting boundary. A set-based ordering of recent SYSTEM postings finds candidate predecessors, and an inner
primary-key range join rejects candidates with intervening postings, including
those whose settlement time is outside the window.
Daily raw posting deltas and the ledger-transaction heatmap remain raw measures.

## Limits and ownership

Adjacency is a deterministic aggregate heuristic, not forensic evidence of who
paid whom. Concurrent unrelated equal-value payments can look identical; legitimate
nonadjacent transfers can remain uncollapsed. The UI labels the estimate. Do not
use these pairs to persist sale links, identify a payment in an audit, or rewrite
the ledger. PAR-235's historical sale-link backfill independently requires unique
leg pairs and unique sales, including adjacent batches and already linked sales.

The query uses the existing settlement-time and posting indexes, bounded by the
selected reporting window, without a migration, backfill, pairing table, worker,
or transfer-hot-path trigger. SQL integration tests exercise classification,
raw-row preservation, calendar/window boundaries and actual query-plan row visits
against 10,000 unrelated old SYSTEM postings and 1,000 current pairs. Correlated
predecessor lookups were rejected after the latter fixture exposed quadratic
work; the range-join guard keeps the matching rule without that repeated scan.
Production-scale latency and match rates still need read-only observation after normal rollout; no production or
shared staging execution is part of this change.
