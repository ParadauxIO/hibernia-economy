---
title: TreasuryAPI and service configuration
order: 15
description: Configure the API key issuer, Explorer, REST API, identity, caches, and monitoring.
---

# TreasuryAPI and service configuration

Keep a configuration inventory per tenant. Shipped files are examples, not the
current production values. Change secrets in their authoritative store (for example,
Infisical when it manages the deployment), then restart the consuming services.
Do not edit a generated Kubernetes Secret and assume the change will persist.

## TreasuryAPI plugin

`plugins/TreasuryAPI/config.yml` controls the in-game key issuer and web identity
integration. There is no TreasuryAPI reload command; restart after configuration
changes. Its complete template is
[config.yml](https://github.com/ParadauxIO/hibernia-economy/blob/develop/treasury-api-plugin/src/main/resources/config.yml).

| Settings | Required behavior |
|---|---|
| `database.host`, `.port`, `.database`, `.username`, `.password` | Same economy schema as Treasury; replace the bundled password. `TREASURYAPI_DB_HOST`, `_DB_PORT`, `_DB_NAME`, `_DB_USERNAME`, `_DB_PASSWORD` override file values. |
| `api.jwt-secret` | Byte-identical to the REST API's `JWT_SECRET`; use `base64:` followed by random key material. Changing it invalidates all old tokens. |
| `keycloak.enabled` | Default `false`; enable account-management/password-recovery integration deliberately. |
| `keycloak.base-url`, `.realm`, `.client-id`, `.client-secret` | Admin service-account connection. `TREASURYAPI_KEYCLOAK_CLIENT_SECRET` overrides the secret. This is separate from Explorer's OIDC public client. |
| `reconciliation.enabled` | Default `true`; required with LuckPerms for synchronized web roles/account relationships. |
| `reconciliation.interval-seconds` | Default 1800; synchronization runs at startup and at most 30 minutes apart. Unrefreshed LP grants expire after one hour. |

See [access recovery](/docs/admin/access-and-credentials) for Keycloak requirements,
verification and token rotation, and [roles](/docs/admin/roles) for sync semantics.

## Economy Explorer

Use [the complete environment template](https://github.com/ParadauxIO/hibernia-economy/blob/develop/economy-explorer/.env.example)
for every variable and default. Required connection/sign-in settings are:

| Area | Variables and checks |
|---|---|
| Database | `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`; `DB_POOL` defaults to 100, `DB_STMT_TIMEOUT_S` to 15 seconds. Budget connections across all replicas/services. |
| OIDC | `AUTH_KEYCLOAK_ISSUER`, `AUTH_KEYCLOAK_ID`, `AUTH_KEYCLOAK_SECRET` (empty for the public client), `AUTH_SECRET`, `AUTH_URL`; configure the client's callback at `<AUTH_URL>/api/auth/callback/keycloak`. `AUTH_TRUST_HOST=true` is for a trusted proxy deployment. |
| Tenant/URLs | `TENANT`, `THEME`, `SITE_URL` (defaults to `AUTH_URL`), `SERVER_NAME`, `SERVER_IP`, `SERVER_ICON`, `CURRENCY_SYMBOL`; verify the correct tenant branding and canonical URL. |
| Admin API | `TREASURY_API_BASE_URL` and `TREASURY_ADMIN_TOKEN`; missing either disables admin writes and webhook management. |
| Maps/assets/status | `MAP_URL`, `MAP_ZOOM`, `ITEM_ICON_BASE`, `MC_STATUS_API`; verify links against this server's worlds and assets. |
| Cache | `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`; without Redis, the app uses an in-process cache. |
| Observability | `PROMETHEUS_URL` for API-key usage; `UMAMI_SCRIPT_URL` plus `UMAMI_WEBSITE_ID` for analytics; `FARO_COLLECTOR_URL` for browser telemetry. Unset disables each integration. `BUILD_YEAR` is optional footer metadata. |

**API base URL:** use the origin plus any gateway prefix, without `/api/v1`:
`http://treasury-rest-api:8080` internally, or `https://api.example/economy`
through a gateway. Admin clients append `/api/v1/...`; appending it yourself
produces a duplicate path and failed writes. Public tax-rate fetching tolerates
that suffix, so a working rates panel alone does not validate admin configuration.

`AUTH_SECRET` signs website sessions; it is not the Treasury JWT signing secret.
The Explorer needs a SERVICE token, not `JWT_SECRET`. Never enable `E2E_TEST_AUTH`
in a deployment: it is a test-only authorization bypass.

The Explorer reads economy data, writes its access/audit control records and
sends financial/admin operations to REST. Give its database identity the grants
required by that implementation; a blanket read-only identity will break role,
link and audit operations. Do not grant ledger-write access as an admin-API fix.

## Treasury REST API

[The environment template](https://github.com/ParadauxIO/hibernia-economy/blob/develop/treasury-rest-api/.env.example)
lists all options. Spring does not automatically read `.env`; inject variables
into the process/container. The Docker image uses the `prod` profile.

| Area | Configuration |
|---|---|
| Database | `DB_URL` (JDBC MariaDB URL), `DB_USERNAME`, `DB_PASSWORD`; production pool defaults `DB_POOL_SIZE=20`, `DB_POOL_MIN_IDLE=5`. |
| Auth | `JWT_SECRET` must match TreasuryAPI; placeholder/short secrets are refused. |
| HTTP | `SERVER_PORT=8080`, `MANAGEMENT_PORT=8081`; `TOMCAT_MAX_THREADS=200`; trusted forwarded-header handling via `SERVER_FORWARD_HEADERS_STRATEGY`. Keep management endpoints private. |
| Rate limiting | `RATE_LIMIT_REDIS_HOST`, `_PORT`, `_PASSWORD`; without Redis limits are per-process. Use shared Redis for multiple replicas. |
| Feed | `FEED_SETTLEMENT_LAG_SECONDS=3`; recent settlements are withheld to avoid cursor gaps. |
| Webhooks | `WEBHOOK_ENABLED=true`, poll every `WEBHOOK_POLL_MS=2000`; concurrency, batch, timeout, retry and failure settings are in the template and [webhook runbook](/docs/admin/webhooks#delivery-incident-procedure). |

The `uat` profile uses `UAT_` prefixes for database, signing, HTTP and rate-limit
variables; feed/webhook variables keep their plain names. Check the deployed
profile before diagnosing an apparently ignored variable.

## Verify and revert a configuration change

Back up the previous nonsecret settings and retain the previous secret version
securely. Restart only the affected services, check startup failures, then test
read access, sign-in and the affected operation on staging. Confirm rejected
access stays rejected. Restore the previous settings and restart if validation
fails; coordinate signing-secret rollback across TreasuryAPI, REST and SERVICE
tokens rather than changing only one consumer.

### Explorer request blocking

`BLOCKED_IPS` accepts comma-separated IPv4/IPv6 addresses and CIDRs.
`BLOCKED_UAS` matches complete, case-sensitive User-Agent values;
`BLOCKED_UA_SUBSTRINGS` matches case-sensitive literal substrings. Empty lists
allow traffic. Invalid IP rules fail initialization. Changes require a restart.
For example, the historical scraper rules are `BLOCKED_IPS=136.107.101.57` and
`BLOCKED_UAS=Blockantir-BalanceCollector/1.0`; these are examples, not defaults.
Each tenant can configure its own lists on the shared image.

IP rules require `BLOCK_TRUST_ENVOY=1`. This trusts only
`x-envoy-external-address`, never X-Forwarded-For. The reviewed GitOps topology
uses a Cilium hostNetwork gateway and a ClusterIP Explorer service. Its production
`scraper-block.yaml` documents Envoy `useRemoteAddress=true`, zero trusted XFF
hops, and replacement of the external-address header. NextRequest does not expose
the socket peer, so the app cannot authenticate that header itself. Before
activation, verify the deployed Envoy configuration and restrict direct ingress
to the app to the gateway; a ClusterIP alone does not enforce that restriction.
Do not enable trust for a directly reachable app or a gateway that preserves this
header. Missing, malformed or multiple client addresses return 403 when IP rules
are active. UA blocking works independently of proxy trust.

Blocks run before auth, redirects and route rendering, returning a plain 403 with
`Cache-Control: no-store`. Structured JSON logs have `kind: "blocked"`, tenant,
method, path, resolved IP, UA, reason and status. Query strings and cookies are
omitted. Static assets, image optimization, favicon, robots and liveness remain
outside the proxy matcher. Authentication endpoints are included.
Deployment, network policy and tenant rule activation are separate operations;
this change does not alter GitOps or existing gateway blocks.
