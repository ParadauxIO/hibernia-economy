# Public tax configuration publication (PAR-499)

`ConfigController` exposes unauthenticated GET `/api/v1/config/tax`, reading the
snapshot Treasury publishes to `economy_config` on startup and reload.
`TaxConfigPublicationTest` executes in the isolated tax profile because it edits
shared Treasury configuration. It restores the original YAML and reloads in a
finally block.

The live journey writes intentionally unsorted balance brackets and plugin rates,
then runs real console `treasury reload`. The public JSON must show sorted exact
floor/rate pairs, exact income default/plugin rates and enabled flags. Both
updatedAt fields must parse as instants. An unchanged reload preserves the whole
response including timestamps. An invalid income rate must not publish any
partial replacement. A subsequent valid reload disabling both sections changes
the flags while retaining the configured rates. No ledger transaction is created.

This proves Paper reload → shared published snapshot → public HTTP read. It does
not independently certify tax collection at these rates, all-sections-absent startup, publication failure recovery, cross-section concurrent reload atomicity,
restart publication, anonymous throttling or exact timestamp change ordering.
The neighboring `TaxCycleLifecycleTest` covers Business tax collection separately.
See `results.md` for execution evidence.

## Missing and malformed published sections

Four additional cases remove or corrupt `tax.balance.brackets` or
`tax.source-income-tax.plugin-rates` in the disposable database. These are
explicit published-data fault fixtures, not a claim that normal Paper publishing
creates malformed JSON. Public GET must remain 200, return an explicit null for
the affected section, and preserve the other section byte-for-JSON-value including
its timestamp. Real `treasury reload` in finally restores the publication. The
recovered rates/flags must equal the original, its update time must not move
backwards, and the unaffected section remains identical. No ledger transaction
may be created. Both-absent startup, structurally valid but unexpected JSON types,
publication write failures and concurrent reads/reloads remain separate gaps.
