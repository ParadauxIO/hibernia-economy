# Explorer documentation coverage (PAR-498)

Initial audit baseline: `develop` **99c7a0b5a07d5c509952c2f94bc394e52c164770**,
verified against the remote branch on 2026-10-20. Realty was checked at the
repository's pinned **95eba03c1041f57e79f9f0a659d9d392d0f2ddab**, not its newer
upstream head. Tracking: [PAR-498](https://app.taskl.ie/w/paradaux/issues/PAR-498).

This is a source-to-documentation review. It does not certify production tenant
configuration or execute the operational actions described. Rates/defaults are
labelled as such; published Treasury rates use the existing live blocks.

## Main-to-develop follow-up

The follow-up explicitly compares `main`
**36d7d90aa9e9eb1e4e193051f215a37dec1c0cbc** (also the merge base) with
`develop` **2c80bfc3d8c1de13b1dbc35236153446e423d865**, 528 commits ahead.
Commit history, changed command routes, application pages, configuration and
operational changes were cross-checked against the source coverage map below.
Related features share a guide/reference section; internal refactors, dependency
updates and test-only changes do not need individual player documentation pages.

| Develop additions/changes | Documentation destination |
|---|---|
| Economy command, personal/firm sales, notifications, export links, firm chat/spy, firm baltop | Treasury/Business references; business setup and configuration |
| Firm aliases, name filtering/reservation/release, account reconciliation and creation failure handling | Firm-shop/setup guides; Business reference/configuration; incidents |
| PlaceholderAPI balances and Java firm/account resolution | Business configuration; API guide's plugin integration section and linked Java API notes |
| Finder, previews, custom items, stock/capacity, WorldEdit/FAWE, legacy import and retained add-ons | ChestShop guide/reference/configuration; cutover and incident procedures |
| Latest native finder parity (PAR-496): search aliases/completion, inspector, full candidate ranking, console resync, preview resize | ChestShop reference and configuration; incidents |
| Configurable web roles/groups, LP sync, account relationships, password recovery | Roles; permissions; Explorer guide; TreasuryAPI reference; credentials runbook |
| Transaction counterparties and latest account-based transaction-detail access | Explorer guide; roles; administration and incident procedures |
| Explorer overview/wealth/taxation, UTC aggregation/local timestamps, market activity/catalogue/comparison, mobile directories | Using the Explorer; tax guide; Treasury configuration |
| Public baltop/tax/market APIs, firm-scoped tokens, feed/idempotency, webhooks, SERVICE admin writes and rate limits | API/webhook guides; TreasuryAPI reference; credentials/services/admin procedures |
| Tax/payroll reloads, allocation, AFK and government fine notifications | Tax/salaries guides; Treasury/Business configuration; operator commands/incidents |
| Pinned Realty 2.0 parties, leases, tax rules, subregions, modules and preview format | Realty guide/reference/configuration and upgrade runbook |
| Schema changes, deployment bundle, coordinated rollout and four-database staging refresh | Release/recovery runbook and linked repository deployment procedures |

This pass corrected an additional stale price-cap claim (the upstream ChestShop
price-limit/discount systems were removed), clarified explicit fine destinations,
added Java API discovery, and incorporated the finder and transaction-access
changes merged after the initial baseline. The follow-up changes are documentation
only. Before PR creation, the branch was rebased onto `2c80bfc` and the local
application checks below were repeated. New upstream game behavior was checked
against its source, not exercised against a deployed game server.

## Coverage map

All 35 pre-existing Markdown pages were read. Section indexes, overview and FAQ
were checked for navigation/discovery as well as content. The source areas below
are the maintenance points when changing a corresponding feature.

| Feature / source of truth | Explorer documentation |
|---|---|
| Treasury commands, `plugin.yml`, `AccountResolver`, account access services | Getting started; Treasury reference; government guide/admin access; operator commands; accounts/permissions/glossary |
| Treasury tax/salary configs, `TaxCommand`, cycle scheduler, balance/source-income services and salary task | Tax and salaries guides; Treasury configuration; operator commands; incidents |
| `Business` command handlers, roles/account access, name filter, tax allocator, PlaceholderAPI/chat integrations | Business setup/accounts/hiring/shop guides; Business reference/configuration; Explorer administration; incidents |
| `TreasuryAPICommand`, UI/password/key handlers, plugin config/permissions | TreasuryAPI reference; Explorer linking/password guide; API guide; access and credentials; service configuration |
| ChestShop command handlers/configuration, `EconomyServiceImpl`, finder and import/recovery services | ChestShop guide/reference/configuration; firm shop guide; incidents and cutover links |
| Pinned Realty command registrations, settings/taxes, `PropertyTaxPolicy`, parties, set/lease routing, preview capture and 2.0.0 release notes | Property guide; Realty reference/configuration; Realty operations and upgrades |
| Explorer page routes, SQL/services, `lib/auth/policy.ts`, relationship checks, role/group services and admin actions | Using the Explorer; roles; Explorer administration; webhook admin; runbook |
| REST controllers/services, issuer rate-limit interceptor, idempotency checks and webhook dispatcher | API/webhook guides; access and credentials; service configuration; webhook incident procedure |
| `.env.example` files, Auth.js setup, `lib/treasury.ts`, mint script, `/healthz` and management configuration | Service configuration; access and credentials; daily operator checklist |
| Flyway workflows/migrations (through V36), `ops/money-invariants.sql`, release guide and staging refresh | Release and recovery; incidents; runbook |
| Docs renderer, fixed admin sidebar, page permission gate and sitemap tree | Public indexes/reference link; admin landing/sidebar links; docs unit/browser checks |

## Material corrections

1. **Tax configuration:** ChestShop percentages are read from its own `TAX_AMOUNT`
   and `SERVER_TAX_AMOUNT`; Treasury supplies collection/destination. Realty uses
   ordered per-rule formulas/exemptions and a cubic shipped catch-all, replacing
   the documented top-level/exponential model. Business allocates tax cents by
   largest remainder, not by folding all drift into the largest account.
2. **Operator commands:** removed nonexistent `tax trigger balance` and `find resync`
   instructions; corrected typed account arguments, ChestShop resync's all-world
   scope/work budget, and the unsupported RedProtect setting. Manual tax triggers use new
   timestamps and can charge again, despite deduplication of a single period.
3. **Permissions/privacy:** retired viewer means player, not global oversight;
   proprietor-only account management is distinct from ADMIN staff permissions;
   government authorizers still need management command rights. Staff/private
   data access and chat oversight are no longer described as owner/member-exclusive.
4. **Missing features:** password recovery, TreasuryAPI commands/config, firm-wide
   API key scope, firm baltop/chatspy, REST public market/tax/firm/feed and safe
   retries, finder aliases, and the current Realty parties/leases/subregions/modules/
   preview workflows are covered.
5. **Operations:** added the central runbook and linked service configuration,
   account/firm administration, credentials, incident, release/recovery and Realty
   upgrade procedures. Detailed existing export/restore/cutover procedures remain
   authoritative and linked rather than copied as divergent scripts.
6. **Examples and discovery:** corrected the API-base example that duplicated
   `/api/v1` on admin calls; guarded webhook signature comparisons against missing/
   wrong-length headers; use delivery IDs instead of transaction-only deduplication.
   New pages are reachable through the existing gated navigation.

## Keeping it current

For a command, permission, default, endpoint or migration change, update both the
player-facing explanation and affected operator procedure. Read commands and
implementations rather than relying on old comments or release-plan documents.
Submodule updates require checking their pinned revision's breaking changes too.
Avoid presenting example config as live tenant state. Validate destructive
procedures on isolated staging and record version-specific prerequisites.

`test/unit/docs.test.ts` renders every page and validates documentation links and
anchors, public tree coverage and admin sidebar targets. Existing sidebar tests
check `docs.admin.read`, including custom permission bundles. `test/e2e/docs.spec.ts`
checks player recovery/reference discovery and admin gating/navigation on desktop
and mobile. They do not replace source review for prose semantics or live game,
Keycloak, webhook, upgrade and disaster-recovery acceptance tests.

## Verification evidence

The implementation was checked locally on the audited baseline plus the PAR-498
changes. The 142 unit tests and 11 documentation sidebar component tests passed;
the documentation checks render all 43 pages and resolve their internal links
and anchors. Linked repository files were also checked against the local audited
revisions.

The production build and 10 Playwright documentation checks passed against a
disposable MariaDB fixture at `http://127.0.0.1:31498`, using desktop Chromium and
Pixel 7 emulation. They exercise public reference/recovery navigation, admin
navigation and reload, and denial of runbook content/navigation to anonymous,
player and moderator identities. No console/runtime errors or unexpected failed
requests were observed. These checks use the existing test authentication shim;
they do not exercise production Keycloak or real player accounts.

After rebasing onto `2c80bfc`, production build, typecheck, lint, all 152 unit tests,
11 sidebar component tests and 10 documentation browser tests passed. PR screenshots
compare an isolated `2c80bfc` admin landing page with the rebased documentation
build, using the same synthetic admin, viewport and theme; the new runbook is
shown separately.
