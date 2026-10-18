# Release runbook — `develop` → `main` (schema V16+)

How to ship the release that takes a production database from **V15** to the
current head (`V16`…`V29` at the time of writing). It is a **full-stop release**:
there is no order in which old and new code can both run against the same schema.

For the general mechanics see [database.md](database.md) (Flyway workflows) and
[ci-cd.md](ci-cd.md) (image builds, Argo CD). This page is the order of operations.

## Why this cannot be a rolling deploy

| Fact | Where |
|---|---|
| Merging to `main` builds and pushes `production-sha-<short>` (+ `:prod`) images for `economy-explorer` and `treasury-rest-api`. Argo CD Image Updater rolls any new `production-sha-*` tag into the production overlay on its own. | `.github/workflows/economy-explorer-docker.yml`, `treasury-rest-api-docker.yml` |
| One REST image and one explorer image serve **every** production tenant, so all tenants pick up the new code at the same moment. | comment in `treasury-rest-api-docker.yml` ("Compute tags") |
| Schema deploys are manual, one tenant per run. | `.github/workflows/economy-flyway-migrate.yml` |
| The Paper plugins (Treasury, Business, ChestShop, TreasuryAPI) are not deployed by CI at all — jars are copied to the server and load on restart. | [build-and-test.md](build-and-test.md#the-release-bundle) |
| **New code needs the new schema:** it reads `economy_players` (V17), the `account_access` *table* (V18) and the `account_read_access_api` / `_web` views (V22). On a V15 database those queries fail. | `treasury`, `business`, `treasury-rest-api`, `economy-explorer` |
| **Old code needs the old schema:** the code on `main` today reads `firm_players` and `player_login_times` (explorer, REST API, Business, Treasury), which **V19 drops**. V18 drops `account_members`, `account_authorizers`, `account_group_members`, `account_group_authorizers` — the tables a pre-`account_access` Treasury build reads and writes. | `V18__…sql:92-95`, `V19__…sql:17-18` |

Other destructive steps in the same run: V23 nulls every stored `api_keys.token`,
V28 drops `firm_sale` (unused by any code, old or new), V29 deletes duplicate
`government_fines` rows. MariaDB DDL is not transactional, so a migration that
fails midway leaves the earlier ones applied — **the pre-migration backup is the
only way back.**

## How long it takes

Plan a **two-hour window and expect about one hour**. Only V36 scales with the
ledger (19.7 M postings, 9.9 M transactions): it reads all of it once to backfill
`ledger_txn_volume`. No pending migration alters the ledger tables themselves.

| Step | Expect |
|---|---|
| Stop the game server, REST API and explorer; confirm no connections | 5 min |
| Back up and verify (7.6 GB of data + indexes on disk) | 10–15 min |
| Run V16–V36 | 5–15 min |
| Swap jars, set the new secret, start everything | 10 min |
| Re-mint the explorer's SERVICE token | 5 min |
| Verification (step 6) | 15 min |

The migration figure comes from a synthetic database with production's row counts
and its 128 MB buffer pool: about 35 s for V20's orphan checks over 3.3 M shop sales,
and seconds for everything else. A rehearsal on 2026-10-18 on a copy of production's
economy database (snapshot of 2026-10-17), also with a 128 MB buffer pool, ran
V16–V36 in 233 s: 191 s for V36's backfill, 23 s for V20. The backfill adds about
730 MB to the database. (V20 adds its foreign keys with
`foreign_key_checks = 0`, an in-place change; with checks on, the same step copied
the table and took 3.5 minutes on fast storage, far longer on slow.) The second
hour is for a rollback: restoring the dump rebuilds every index, which on a small
buffer pool is the slowest thing that can happen in the window.

## 0. Before the window

1. **Freeze `develop`.** The release is one commit; note its SHA.
2. **Read the plan.** Run **Economy Flyway · Info (read-only)** for each target
   (`democracycraft`, `statecraft`) with *Use workflow from* = `develop`.
   - Expect `V1`–`V15` *Success* and everything from `V16` up *Pending*. The
     `flywayValidate` step then fails with "resolved migration not applied" —
     that is just the pending list and is expected (the step is non-fatal).
   - **Stop** if `V16`, `V17` or `V18` already shows as applied on a target, or if
     validate reports a **checksum mismatch**. `V17` and `V18` were edited
     after they first merged (older copies are on `main`); that is only safe on a
     database that has never run them. Such a target needs a deliberate
     `flywayRepair`/manual reconciliation first — do not continue on autopilot.
3. **Pre-check the data the migrations assume.** A violated assumption fails the
   run partway (V17, V24) or silently drops someone's access (V18). Every row must
   be `0`, on every target:
   ```sql
   -- V17: economy_players.name_lower is UNIQUE
   SELECT 'names colliding case-insensitively', COUNT(*) FROM (
     SELECT 1 FROM firm_players GROUP BY LOWER(current_name) HAVING COUNT(*) > 1) t
   -- V18: account_access is keyed (account_id, subject) and built from account_members
   UNION ALL SELECT 'duplicate account members', COUNT(*) FROM (
     SELECT 1 FROM account_members GROUP BY account_id, member_uuid_bin HAVING COUNT(*) > 1) t
   UNION ALL SELECT 'active authorizers with no active member row', COUNT(*)
     FROM account_authorizers au
     LEFT JOIN account_members m ON m.account_id = au.account_id
          AND m.member_uuid_bin = au.authorizer_uuid_bin AND m.left_at IS NULL
    WHERE au.revoked_at IS NULL AND m.account_id IS NULL
   -- V24: UNIQUE over SYSTEM account names
   UNION ALL SELECT 'duplicate SYSTEM account names', COUNT(*) FROM (
     SELECT 1 FROM accounts WHERE account_type = 'SYSTEM'
      GROUP BY display_name HAVING COUNT(*) > 1) t;
   ```
   V29 removes duplicate fine rows by itself; to see whether it will,
   `SELECT COUNT(*) FROM (SELECT 1 FROM government_fines GROUP BY txn_id HAVING COUNT(*) > 1) t;`
4. **Build the plugin jars** from the release SHA: `./gradlew release` → `release/`
   (Treasury, Business, TreasuryAPI, ChestShop).
5. **Generate the new token-signing secret** (`openssl rand -base64 48`, used as
   `base64:<value>` — see step 5 below). The explorer's SERVICE token is
   re-minted against it in step 5 with
   `economy-explorer/scripts/mint-service-token.mjs`.
6. **Disable any scheduled run of `ops/backfill-firm-account-owners.sql`.** Copies
   older than this release write `account_members` / `account_authorizers` and
   fail once V18 has run.
7. **Tell API users** their tokens will stop working and must be reissued in-game.

## 1. Hold the production rollout, then merge

1. Stop Argo CD from rolling new images into production (pause Image Updater /
   auto-sync for the explorer and REST API apps). The manifests live outside this
   repo.
2. Merge the release PR. Wait for these runs on `main` to go green:
   **Economy Explorer · Build and Push Docker Image to Harbor**, **Treasury REST API · Build and
   Push Docker Image to Harbor**, **Treasury · Build & Publish API**,
   **Business · Publish API**.

The old pods keep serving the old schema while the images build. If the rollout
cannot be held, do step 2 *first* and merge afterwards — the invariant is only
that nothing new starts before step 4 and nothing old runs after step 3.

## 2. Stop everything that touches the database

- Stop the **game server(s)** — that is what unloads Treasury, Business, ChestShop
  and TreasuryAPI. Disabling individual plugins is not enough.
- Scale **`treasury-rest-api`** and **`economy-explorer`** to zero for every
  production tenant.
- Confirm no application connections remain (`SHOW PROCESSLIST`).

## 3. Back up, then migrate — per tenant

1. Take a full logical backup of the tenant database (`mariadb-dump
   --single-transaction --routines --triggers <db>`), and verify the file is
   complete before going on. Triggers matter: balances are trigger-maintained.
   Note two numbers for step 6: `SELECT COUNT(*) FROM firm_players;` and
   `SELECT COUNT(*) FROM account_members WHERE left_at IS NULL;`.
2. Run **Economy Flyway · Migrate**: *Use workflow from* = `main`, `target` = the
   tenant, `confirm_target` = the same name typed out, `baseline` = **off**.
   The job prints `flywayInfo` before and after; the "after" must show every
   migration *Success* and nothing pending.
3. Repeat for the other tenant. Both must be migrated before any new code starts.

If a migration fails: stop, do not retry blind. Go to [Rollback](#rollback).

## 4. Deploy the new code

1. Replace the four plugin jars on each game server with the ones from `release/`.
2. Apply step 5 (secret) to the configs now, before anything starts.
3. Release the Argo CD hold so the `production-sha-<merge sha>` images roll out;
   scale the REST API and explorer back up.
4. Start the game server(s).

## 5. Rotate the token-signing secret

The REST API and the TreasuryAPI plugin derive the same HMAC key from one shared
secret (`common/…/JwtKeys.java`); they must be byte-identical.

1. Set `base64:<value>` as `JWT_SECRET` on the REST API (`api.jwt-secret` in
   `application-prod.yaml`) **and** as `api.jwt-secret` in
   `plugins/TreasuryAPI/config.yml`. Restart both (covered by step 4).
2. Every existing token stops verifying. Players and firms reissue in-game:
   `/treasuryapi personal reissue <keyId>` / `/treasuryapi business reissue <keyId>`
   (`… list` shows the IDs). Tokens are shown once and no longer stored (V23), so
   there is nothing to recover from the database.
3. Re-mint the explorer's **SERVICE** token against the new secret with
   `economy-explorer/scripts/mint-service-token.mjs` (no player can issue or manage a SERVICE key, so it
   is minted offline). Per tenant:
   ```bash
   # the existing key:
   #   SELECT key_id, bin_to_uuid(owner_uuid_bin), revoked, expires_at
   #     FROM api_keys WHERE key_type = 'SERVICE';
   JWT_SECRET='base64:<value>' node economy-explorer/scripts/mint-service-token.mjs \
       --key-id <key_id> --owner <owner uuid> > token.txt 2> apply.sql
   mariadb … <db> < apply.sql      # must report 1 row changed
   ```
   Set the contents of `token.txt` as the explorer's `TREASURY_ADMIN_TOKEN` and
   restart it, then delete the file. The token is only accepted once `apply.sql`
   has run, and running it invalidates the previous token. If no SERVICE row
   exists, `--create --owner <uuid>` prints the `INSERT` for one. Tokens last 180
   days unless `--days` says otherwise (pass the lifetime the existing key was
   given if it should stay long-lived); re-mint the same way before `expires_at`. Until this is done the
   explorer's admin actions and webhook management fail; read-only pages are
   unaffected.

## 6. Verify

- **Schema:** the Migrate run's final `flywayInfo`, or **Info** again — nothing pending.
- **Money:** run `ops/money-invariants.sql` against each tenant; `status` must be `OK`.
- **Player directory:** `SELECT COUNT(*) FROM economy_players;` ≥ the pre-migration
  `firm_players` count. Rows whose `current_name` is a 32-character hex string are
  login-only players seeded by V17; they fix themselves on next login.
- **Access:** `SELECT level, COUNT(*) FROM account_access WHERE removed_at IS NULL
  GROUP BY level;` — MEMBER + AUTHORIZER should match the old active
  `account_members` count.
- **REST API:** `GET /actuator/health` is `UP`; an old token gets 401, a reissued one works.
- **Explorer:** `GET /healthz`; an account page, a firm page and `/chestshop` render
  with player names; sign in and open `/me`.
- **In game:** `/balance`, a `/pay`, one ChestShop purchase, `/firm sales summary`.
  Server log shows all four plugins enabled with no SQL errors.

## 7. After the release

- Run `ops/backfill-firm-account-owners.sql` **once** per tenant. It repairs firm
  accounts stranded by proprietor changes made before this release; the new code
  keeps them aligned from here on. It is idempotent — a second run changes nothing.
- Keep the backup until the release has been stable for a few days.

## Rollback

There is no down-migration, and the drops are irreversible without the backup.

- **Before step 3.2 (nothing migrated):** revert the merge on `main` (or leave the
  rollout held), start the old code again. Nothing was lost.
- **After a failed or completed migration:** keep everything stopped, restore the
  tenant from the step 3.1 backup, redeploy the previous images (the mutable
  `:prod` tag has moved — pin the previous `production-sha-*` tag) and the previous
  plugin jars, and put the previous signing secret back. Any transaction made on
  the new code after go-live is lost by the restore, so decide quickly: once
  players have been let back in, fixing forward is almost always cheaper.
- Fix the migration on `develop` as a **new** version where possible; only edit an
  existing file if it has not been applied to any persistent database.
