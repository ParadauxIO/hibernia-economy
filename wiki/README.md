# Hibernia Economy — Developer Wiki

Technical documentation for people building **on** or **inside** Hibernia Economy.
This is a plain folder in the repo (not a GitHub wiki) so it versions alongside
the code.

> Looking for the **player / server-admin** guide instead? That's the user-facing
> docs in [`economy-explorer/docs/`](../economy-explorer/docs/index.md), served
> live at `/docs` on any Economy Explorer deployment.

## Contents

- **[Architecture](architecture.md)** — the big picture: the ledger as source of
  truth, how components talk, and how money flows.
- **[Components](components.md)** — what each project is, its stack, and where
  things live inside it.
- **[Build & test](build-and-test.md)** — the single root Gradle build, every
  build task, ChestShop's shading, and how to run the tests.
- **[Database](database.md)** — the shared schema, Flyway migrations, and how
  deploys are gated.
- **[CI/CD](ci-cd.md)** — the GitHub Actions workflows, container builds, and the
  release flow.
- **[Release runbook](release-runbook.md)** — the order of operations for a
  release that changes the schema: backup, stop, migrate, deploy, verify, rollback.
- **[Conventions](conventions.md)** — the rules that keep the monorepo coherent.

## Quick links

- [Repository README](../README.md)
- [Contributing](../CONTRIBUTING.md) · [Security](../SECURITY.md)
- [Discord (Hibernia Economy)](https://discord.gg/b88K3ETeHS) — support, ideas, and
  issue tracking (we use **Tesks**, not GitHub Issues)

## Orientation in 60 seconds

- **One ledger.** `treasury` owns every balance and transaction. Nothing else
  moves money except through `TreasuryApi.transfer(...)`.
- **One database, one schema.** `economy-flyway` owns the schema; every service
  points at the same MariaDB.
- **One build.** A single root Gradle build (`./gradlew build`) compiles and tests
  every JVM project; `economy-explorer` is the lone Node/npm project.
- **Two doc sets.** This wiki is for developers; `economy-explorer/docs` is for
  players and admins.
- **`develop` is home.** Work targets `develop`; `main` is only for release tags.
