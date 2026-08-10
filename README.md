# Hibernia Economy

A complete, server-grade economy for Minecraft networks — a double-entry ledger,
companies and staff, automated shops, property, taxation, a REST API, and a
public web explorer, all in one place.

Aren't economies on Minecraft servers kind of plain and boring? Really
superficial in their design? In March 2025 I set out to fix that for
DemocracyCraft / The Minecraft Cities Network, and after just over a year it
shipped to the live servers. This repository is that work — a set of connected
plugins and services that together run the money on a server.

<p align="left">
  <a href="https://discord.gg/b88K3ETeHS"><strong>💬 Discord (Hibernia Economy)</strong></a> ·
  <a href="wiki/README.md"><strong>📖 Developer Wiki</strong></a> ·
  <a href="economy-explorer/docs/index.md"><strong>📘 User Guide</strong></a>
</p>

---

## What's in the box

Hibernia Economy is not a single plugin — it's a small ecosystem built around one
idea: **there is exactly one ledger, and it is the source of truth.** Every other
piece either records into that ledger through a published API, or reads from it.

- **Treasury** keeps the books — a real double-entry ledger of every balance and
  transaction.
- **Business** lets players form companies, hire staff with roles, and hold
  company money.
- **ChestShop** turns signs into automated shops that move money through Treasury.
- **Realty** handles land — buying, renting, offers, and auctions.
- **A REST API** exposes the ledger over HTTP for tooling and integrations.
- **The Economy Explorer** is a public website to browse balances, prices, and
  money flow, and hosts the player-facing documentation.
- **One Flyway-managed schema** is the single shared database all of the above
  agree on.

## Architecture at a glance

Treasury is the **system of record**. No other component stores balances or moves
money on its own — money only moves through `TreasuryApi.transfer(...)`. Plugins
talk to each other through slim, published `*-api` jars, never by reaching into
each other's database.

```
                    ┌──────────────────────────────────────────────┐
   in-game  ──────▶ │  Paper plugins                               │
   commands         │   Business ─┐                                │
                    │   ChestShop ─┼─▶ TreasuryApi / TaxApi ─▶ Treasury (the ledger)
                    │   Realty ────┘                         │     │
                    └────────────────────────────────────────┼─────┘
                                                              ▼
                                          ┌──────────────────────────────────┐
   HTTP clients ─▶ treasury-rest-api ────▶│  shared MariaDB                  │
                   (keys via              │  schema owned by economy-flyway  │
                    treasury-api-plugin)  └──────────────────────────────────┘
                                                              │ reads economy data*
   browsers ─────▶ economy-explorer (Next.js) ───────────────┘
```

> \* The explorer never writes the ledger/balances/accounts, but it is not
> strictly read-only over the whole DB — it writes a few control tables
> (webhooks, key revocation, rate-limit overrides) directly today. See
> [`wiki/architecture.md`](wiki/architecture.md#the-read-side).

The public APIs live in dedicated `*-api` subprojects (`treasury-api`,
`business-api`) so downstream code depends on a stable surface, not the plugin
internals.

## Components

| Component | Kind | Responsibility |
|---|---|---|
| **`treasury/`** | Paper plugin | The double-entry ledger and **system of record** for all balances, transactions, government accounts, fines, and tax. Exposes `TreasuryApi` + `TaxApi`. |
| **`treasury/treasury-api/`** | Library jar | The public Treasury API surface (`TreasuryApi`, `TaxApi`, model types) downstream plugins compile against. Published as `io.paradaux:treasury-api`. |
| **`business/`** | Paper plugin | Companies/firms: creation, staff with roles & permissions, firm accounts, sales, and tax — moving money via `TreasuryApi`. |
| **`business/business-api/`** | Library jar | The public Business API (`FirmApi`, `StaffApi`, `RolePermission`, …). Published as `io.paradaux:business-api`. |
| **`treasury-api-plugin/`** | Paper plugin | Issues and manages **JWT API keys** that authenticate callers of the REST API. (Not the same as the `treasury-api` jar.) |
| **`treasury-rest-api/`** | Spring Boot service | A REST/HTTP surface over the ledger for tooling and integrations, with API-key auth and rate limiting. Ships as a Docker image. |
| **`economy-flyway/`** | Flyway migrations | The **authoritative shared database schema**. All schema changes are versioned migrations here; deploys run through gated workflows. |
| **`common/`** | Library jar | Framework-free shared kernel used by the MariaDB-writing modules: `UuidBin` (UUID↔BINARY(16)), `JwtKeys` (the single source of the mint/verify HMAC key derivation), `DataSourceProvider`, `BalanceTaxBrackets`. |
| **`chestshop/`** | Paper plugin (fork) | Sign-based automated shops, integrated with Treasury and Business. A vendored fork of ChestShop-3, built as a single Gradle module against the Paper 1.21.11 API (the old per-server-version adapter modules were folded into the core). |
| **`economy-explorer/`** | Next.js app | The public, read-side web explorer (balances, prices, money flow) **and** the player/admin documentation site served at `/docs`. |
| **`realty/`** | Paper plugin *(submodule)* | Property and land: buying, renting, offers, and auctions. Maintained upstream; consumed here. |
| **`hibernia-framework/`** | Library *(submodule)* | The shared command (Brigadier-based `@Command`), type-safe configuration, and i18n framework the Paper plugins are built on. Published as `io.paradaux:hibernia-framework`. |

> `realty/` and `hibernia-framework/` are git submodules — they live in their own
> repositories and are consumed here as published `io.paradaux:*` artifacts.

## Repository layout

```
hibernia-economy/
├── settings.gradle.kts        # single root Gradle build — includes every JVM project
├── build.gradle.kts           # shared coordinates + centralized plugin versions
├── gradlew / gradle/          # the one Gradle wrapper for the whole monorepo
├── treasury/                  #   └ treasury-api/      (the ledger + its public API)
├── business/                  #   └ business-api/      (companies + its public API)
├── treasury-api-plugin/       # JWT API-key issuer
├── treasury-rest-api/         # Spring Boot REST service (Dockerized)
├── economy-flyway/            # the shared database schema (Flyway migrations)
├── common/                    # framework-free shared kernel (UuidBin, JwtKeys, DataSourceProvider)
├── chestshop/                 # sign shops — single module, shades to chestshop-<version>.jar
├── economy-explorer/          # Next.js explorer + /docs user guide (npm, not Gradle)
└── .github/workflows/         # CI for every component (build, test, publish, deploy)
```

## Documentation

There are two audiences, two doc sets:

- **Players & server admins → the User Guide.** A friendly, in-game-focused guide
  to earning and spending money, running a business, selling through ChestShops,
  property, tax, and every command. It's served live at **`/docs`** on any Economy
  Explorer deployment, and the source lives in
  [`economy-explorer/docs/`](economy-explorer/docs/index.md).
- **Developers & contributors → the [Wiki](wiki/README.md).** Architecture, the
  build system, the database, CI/CD, and the project conventions. It's a plain
  folder in this repo (not a GitHub wiki) so it versions with the code.

## Getting started (development)

### Prerequisites

- **JDK 21** (Temurin recommended). The Gradle wrapper is included — you do **not**
  need a system Gradle.
- **Node.js 22 + npm** — only for `economy-explorer`.
- **MariaDB 10.11+ / MySQL** — to run the services, or for full integration tests.
  (JVM integration tests can also spin up an embedded MariaDB via MariaDB4j, no
  install required.)
- **git** with submodule support, if you need `realty`/`hibernia-framework` from
  source.

### Clone and build

```bash
git clone <this-repo> hibernia-economy
cd hibernia-economy

# Build and test everything (one wrapper, one build graph):
./gradlew build
```

On Windows use `.\gradlew.bat`. The first run downloads the Gradle distribution and
dependencies; later runs are cached.

### The Economy Explorer (web)

```bash
cd economy-explorer
npm ci
npm run dev        # http://localhost:3000  (docs at /docs)
```

Copy `economy-explorer/.env.example` to `.env` and fill in the database/Redis
settings before running against real data.

## Building the plugins

Everything is one Gradle build, so you can build any artifact from the repo root:

```bash
# Paper plugin shaded jars  ->  <project>/build/libs/
./gradlew :treasury:shadowJar
./gradlew :business:shadowJar
./gradlew :treasury-api-plugin:shadowJar

# ChestShop  ->  chestshop/build/libs/chestshop-<version>.jar
./gradlew :chestshop:shadowJar

# REST API (Spring Boot)  ->  treasury-rest-api/build/libs/
./gradlew :treasury-rest-api:bootJar

# Publish an API jar to the local Maven cache (for cross-project work)
./gradlew :treasury:treasury-api:publishToMavenLocal
```

A successful `:treasury:shadowJar` / `:business:shadowJar` also stages the jar into
`server/plugins/` for a local dev server; pass `-Pci=true` to skip that copy.

See **[wiki/build-and-test.md](wiki/build-and-test.md)** for the full task list
and notes on running the test suites.

## Project conventions

- **Branches:** work happens on **`develop`** (the integration branch). **`main`**
  is reserved for release tagging.
- **Namespace:** all JVM code is under `io.paradaux.*`; build coordinates are
  `io.paradaux:<artifact>`.
- **The ledger is authoritative:** never store balances or move money outside
  `TreasuryApi.transfer(...)`.
- **Public API goes in `*-api` subprojects** — never expose plugin internals.

The full set lives in [wiki/conventions.md](wiki/conventions.md).

## Contributing & issue tracking

Contributions are welcome — start with **[CONTRIBUTING.md](CONTRIBUTING.md)** and
the **[Code of Conduct](CODE_OF_CONDUCT.md)**. Note the project runs a
single-maintainer governance model: the lead maintainer has final say on scope and
what lands in the plugins (see the Code of Conduct).

> **This project does not use GitHub Issues.** Work is tracked in **Tesks** (refs
> look like `PAR-123`). To report a bug, request a feature, or pick something up,
> raise it in the **[Hibernia Economy Discord](https://discord.gg/b88K3ETeHS)** —
> a Tesks issue is created from there and linked back to the work.

Found a security problem? Please follow **[SECURITY.md](SECURITY.md)** and do
**not** post it in a public channel.

## Support

Questions, suggestions, or want to help? Join the **Hibernia Economy** Discord —
it's where support, feature requests, and issue tracking all happen:

> **https://discord.gg/b88K3ETeHS**

## License

See [LICENSE.md](LICENSE.md). The published API jars are licensed AGPL-3.0-or-later.
