# Historical ChestShop sale links

[PAR-235's manual backfill tool and operator guide](../ops/sale-ledger-backfill/README.md)
recover unambiguous historical `chestshop_sale.txn_id` links in bounded time
batches. Dry-run is read-only; apply requires a separate explicit operator action.
It is not a Flyway migration and does not run during application startup.

Legacy two-hop trades use the **seller-credit transaction** as the canonical
link, including customer-credit for `SELL`. Both legs must match exact money,
account identities, direction and tight timestamps, with no competing sale or
transaction. Ambiguous and unmatched sales remain NULL with audit reasons.

PAR-234 already supplies primary transaction IDs for new settled sales. PAR-159's
read-only aggregate estimates remain separate from persisted link decisions.
