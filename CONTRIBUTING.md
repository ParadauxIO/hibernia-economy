# Contributing to Hibernia Economy

Thanks for wanting to help! This is a multi-project monorepo that runs a live
server economy, so a little structure keeps everything coherent. Please skim this
before opening your first change.

## Talk to us first — on Discord

**We do not use GitHub Issues.** All work is tracked in **Tesks**, our issue
tracker (refs look like `PAR-123`).

If you've found a bug, have an idea, or want to pick something up:

1. Bring it to the **[Hibernia Economy Discord](https://discord.gg/b88K3ETeHS)**.
2. A **Tesks** issue is created from that conversation and shared back with you.
3. That `PAR-…` ref is what your branch and commits reference.

This keeps discussion, triage, and tracking in one place instead of scattered
across GitHub. No work should land without a tracked issue behind it.

## Branching model

Branch from **`main`** and open pull requests against **`main`**. Work now lands
on main directly; there is no develop integration branch or develop-to-main
release PR.

Keep incomplete features disabled behind [release flags](wiki/feature-flags.md).
Features requiring a database migration must preserve existing behavior while
disabled: Explorer may deploy up to 12 hours before the plugins apply migrations.
An operator enables the feature only after the schema and consumers are ready.

## Before you start

1. Read the [README](README.md) for project layout, prerequisites, and build
   steps, and get `./gradlew build` passing locally.
2. Skim the [developer wiki](wiki/README.md) — especially
   [conventions](wiki/conventions.md) and the page for the component you're
   touching.

## Conventions that matter

These are enforced in review (full list in
[wiki/conventions.md](wiki/conventions.md)):

- **One ledger, one source of truth.** Never store balances or move money outside
  `TreasuryApi.transfer(...)`. Don't let one plugin read or write another's tables
  — go through the published `*-api`.
- **Public API lives in `*-api` subprojects** (`treasury-api`, `business-api`),
  never in a plugin's internals.
- **Layering is one-directional:** entrypoint (command/listener/controller/route)
  → service → persistence (mapper/DAL). Entrypoints never touch persistence
  directly; persistence holds no business logic.
- **Schema changes are migrations** in `economy-flyway/` (a new `V<n>__*.sql`),
  never ad-hoc edits to a derived schema file.
- **Namespace:** all JVM code under `io.paradaux.*`.
- Match the surrounding file's style; keep new code consistent with the module
  you're in.

## Commit messages

- Reference the Tesks issue in **every** commit, e.g.
  `Fix the firm default account (PAR-62)`.
- Write in the imperative mood; explain the *why* when it isn't obvious.
- **Do not** add CI tooling or assistants as co-authors.

## Pull requests

- Keep PRs focused and reasonably small; one logical change per PR.
- Make sure the build and tests pass: `./gradlew build` (use `-PskipIT` for a fast
  unit-only loop; see [wiki/build-and-test.md](wiki/build-and-test.md)).
- Update docs alongside behaviour changes — the
  [user guide](economy-explorer/docs/index.md) for player/admin-facing changes,
  the [wiki](wiki/README.md) for developer-facing ones.
- Move the Tesks issue through its workflow as you go (In Progress → Pending
  Release), so the board reflects reality.

## Security

Never report a vulnerability in a public PR, issue, or Discord channel. Follow
[SECURITY.md](SECURITY.md).

## Questions

Anything unclear? Ask in the
[Hibernia Economy Discord](https://discord.gg/b88K3ETeHS) — that's the fastest way
to get unblocked.
