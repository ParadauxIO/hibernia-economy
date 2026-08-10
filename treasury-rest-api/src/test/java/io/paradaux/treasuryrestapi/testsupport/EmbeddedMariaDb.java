package io.paradaux.treasuryrestapi.testsupport;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

/**
 * One embedded MariaDB (MariaDB4j) for the whole JVM, used by the DB-backed
 * integration tests. Boots lazily on first {@link #ensureStarted()}, creates the
 * subset of tables the API touches, and applies the schema over plain JDBC (no
 * {@code mysql}/{@code mariadb} CLI needed — these DDLs have no triggers).
 *
 * <p>Covers the ledger write side ({@code accounts}, {@code account_balances_mat},
 * {@code ledger_txns}, {@code ledger_postings}) plus {@code firm}/{@code economy_players}
 * for type-aware owner/recipient resolution.
 *
 * <h2>Intentional design: a LIGHT stand-in, not the authoritative schema</h2>
 *
 * The {@link #DDL} below is a deliberately <em>light</em>, hand-maintained stand-in
 * for fast in-repo service/query integration tests — <b>not</b> a copy of the real
 * schema and not applied via Flyway. It creates only the subset of tables (and the
 * two views) this module reads/writes, mirroring the column shapes from the relevant
 * {@code economy-flyway} migrations closely enough for the mappers to bind.
 *
 * <p><b>The authoritative schema is {@code economy-flyway}</b> (the {@code V<n>__*.sql}
 * migrations); it is the single source of truth. This harness does not run those
 * migrations — it exists to keep the in-repo test loop fast and CLI-free.
 *
 * <p><b>The {@code trg_postings_ai} balance-maintenance trigger is intentionally
 * omitted here.</b> In production that trigger is the sole writer of
 * {@code account_balances_mat} (see the ledger-authoritative rules in {@code CLAUDE.md}),
 * but plain JDBC can't run its {@code DELIMITER} block, and these tests don't need it:
 * transfers don't read a balance back after posting, and overdraft checks read the
 * balance that {@link EmbeddedDbIT#seedBalance} seeds directly. So balances here are
 * <em>seeded</em>, never trigger-materialised.
 *
 * <p><b>Full-schema coverage lives outside this monorepo.</b> The authoritative
 * schema + {@code trg_postings_ai} trigger + real balance-materialisation integration
 * is exercised by a separate harness in {@code ../other}. <b>Any test that needs real,
 * trigger-maintained balances belongs in that external harness — not here.</b> Keep
 * this in-repo harness light: unit + light integration + regression only.
 */
public final class EmbeddedMariaDb {

    private static final String DB_NAME = "treasury_rest_test";
    private static volatile String jdbcUrl;
    private static String username = "root";
    private static String password = "";
    private static DB db;

    private EmbeddedMariaDb() {}

    public static synchronized void ensureStarted() {
        if (jdbcUrl != null) return;
        try {
            // CI points us at a real MariaDB service: MariaDB4j's bundled server
            // binary needs system libs the GitHub runner image doesn't ship, so
            // when TREASURY_REST_TEST_JDBC_URL is set we use that DB and skip the
            // embedded server (see .github/workflows/treasury-rest-api-test.yml).
            String envUrl = System.getenv("TREASURY_REST_TEST_JDBC_URL");
            if (envUrl != null && !envUrl.isBlank()) {
                username = envOrDefault("TREASURY_REST_TEST_DB_USER", "root");
                password = envOrDefault("TREASURY_REST_TEST_DB_PASS", "");
                jdbcUrl = envUrl;
            } else {
                DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
                cfg.setPort(0); // free port
                db = DB.newEmbeddedDB(cfg.build());
                db.start();
                db.createDB(DB_NAME);
                jdbcUrl = cfg.getURL(DB_NAME);
                username = "root";
                password = "";
            }
            applySchema();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start embedded MariaDB", e);
        }
    }

    private static String envOrDefault(String key, String def) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : def;
    }

    public static String jdbcUrl() { ensureStarted(); return jdbcUrl; }
    public static String username() { ensureStarted(); return username; }
    public static String password() { ensureStarted(); return password; }

    public static Connection connect() throws Exception {
        ensureStarted();
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    /** Truncate the tables between tests for isolation. */
    public static void truncateAll() {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS = 0");
            for (String t : new String[]{
                    "accounts", "firm", "firm_accounts", "economy_players", "account_balances_mat",
                    "ledger_txns", "ledger_postings", "chestshop_sale", "chestshop_shop",
                    "account_access", "firm_role", "firm_role_permission", "firm_employee",
                    "api_keys", "api_rate_limit_override",
                    "webhook_delivery", "webhook_subscription", "webhook_cursor"}) {
                st.execute("TRUNCATE TABLE " + t);
            }
            // Re-seed the single-row dispatcher watermark that ingest() reads/advances.
            st.execute("INSERT INTO webhook_cursor (id, last_dispatched_txn_id) VALUES (1, 0)");
            st.execute("SET FOREIGN_KEY_CHECKS = 1");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** UUID -> BINARY(16) bytes, matching the production UuidTypeHandler. */
    public static byte[] uuidBytes(UUID u) {
        if (u == null) return null;
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        return bb.array();
    }

    private static void applySchema() throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            for (String ddl : DDL) {
                try {
                    st.execute(ddl);
                } catch (java.sql.SQLException e) {
                    // Tolerate a pre-seeded DB (e.g. a CI MariaDB service reused
                    // across forked test JVMs); truncateAll() handles isolation.
                    if (!e.getMessage().toLowerCase().contains("already exists")) throw e;
                }
            }
        }
    }

    private static final String[] DDL = {
            // Minimal stand-ins for the shared economy tables the API reads/writes.
            """
            CREATE TABLE accounts (
              account_id INT UNSIGNED NOT NULL AUTO_INCREMENT,
              account_type ENUM('PERSONAL','BUSINESS','GOVERNMENT','SYSTEM') NOT NULL,
              owner_uuid_bin BINARY(16) NULL,
              display_name VARCHAR(255) NULL,
              requires_authorization TINYINT(1) NOT NULL DEFAULT 0,
              is_archived TINYINT(1) NOT NULL DEFAULT 0,
              allow_overdraft TINYINT(1) NOT NULL DEFAULT 0,
              credit_limit DECIMAL(19,2) NOT NULL DEFAULT 0.00,
              PRIMARY KEY (account_id),
              KEY idx_acct_owner (owner_uuid_bin)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """,
            """
            CREATE TABLE account_balances_mat (
              account_id INT UNSIGNED NOT NULL,
              balance DECIMAL(19,2) NOT NULL DEFAULT 0.00,
              version BIGINT NOT NULL DEFAULT 0,
              PRIMARY KEY (account_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """,
            // Ledger tables for the write side (transfers). No balance-maintenance
            // trigger here (JDBC can't run DELIMITER blocks) — transfers don't read a
            // balance back after posting, and overdraft checks read the seeded balance.
            """
            CREATE TABLE ledger_txns (
              txn_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
              trade_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              settlement_time DATETIME NULL,
              message VARCHAR(255) NOT NULL,
              initiator_uuid_bin BINARY(16) NOT NULL,
              authorizer_uuid_bin BINARY(16) NULL,
              plugin_system VARCHAR(64) NULL,
              client_dedup_key BINARY(32) NULL,
              PRIMARY KEY (txn_id),
              UNIQUE KEY uq_ledger_dedup (client_dedup_key)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """,
            """
            CREATE TABLE ledger_postings (
              posting_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
              txn_id BIGINT UNSIGNED NOT NULL,
              account_id INT UNSIGNED NOT NULL,
              amount DECIMAL(19,2) NOT NULL,
              memo VARCHAR(255) NULL,
              PRIMARY KEY (posting_id),
              KEY idx_postings_account (account_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """,
            """
            CREATE TABLE firm (
              firm_id INT NOT NULL AUTO_INCREMENT,
              display_name VARCHAR(255) NOT NULL,
              proprietor_uuid_bin BINARY(16) NULL,
              discord_url VARCHAR(255) NULL,
              hq_region VARCHAR(64) NULL,
              default_account_id INT UNSIGNED NULL,
              is_archived TINYINT(1) NOT NULL DEFAULT 0,
              created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              PRIMARY KEY (firm_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """,
            """
            CREATE TABLE firm_accounts (
              firm_id INT NOT NULL,
              account_id INT UNSIGNED NOT NULL,
              added_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              removed_at TIMESTAMP NULL,
              PRIMARY KEY (firm_id, account_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """,
            // Unified player directory (PAR-35) — name resolution reads this now.
            """
            CREATE TABLE economy_players (
              player_uuid_bin BINARY(16) NOT NULL,
              current_name VARCHAR(32) NOT NULL,
              name_lower VARCHAR(32) AS (LOWER(current_name)) VIRTUAL,
              first_seen TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              last_seen TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              last_login_epoch BIGINT NULL,
              PRIMARY KEY (player_uuid_bin),
              UNIQUE KEY uq_economy_players_name (name_lower)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """,
            // ChestShop analytics tables read by the market endpoints — mirrors
            // economy-schema V6__chestshop_sales.sql (the production definition).
            """
            CREATE TABLE chestshop_sale (
              sale_id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
              txn_id            BIGINT UNSIGNED NULL,
              occurred_at       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
              direction         ENUM('BUY','SELL') NOT NULL,
              customer_uuid_bin BINARY(16)      NOT NULL,
              shop_account_id     INT UNSIGNED  NULL,
              shop_account_type   ENUM('PERSONAL','BUSINESS','GOVERNMENT','SYSTEM') NULL,
              shop_firm_id        INT           NULL,
              shop_owner_uuid_bin BINARY(16)    NULL,
              is_admin_shop       TINYINT(1)    NOT NULL DEFAULT 0,
              material          VARCHAR(64)     NOT NULL,
              item_key          VARCHAR(190)    NOT NULL,
              item_name         VARCHAR(255)    NOT NULL,
              item_custom       TINYINT(1)      NOT NULL DEFAULT 0,
              item_data         MEDIUMTEXT      NULL,
              quantity          INT             NOT NULL,
              unit_price        DECIMAL(19,4)   NOT NULL,
              total_price       DECIMAL(19,2)   NOT NULL,
              tax_amount        DECIMAL(19,2)   NOT NULL DEFAULT 0.00,
              world             VARCHAR(64)     NULL,
              sign_x INT NULL, sign_y INT NULL, sign_z INT NULL,
              shop_stock        INT             NULL,
              PRIMARY KEY (sale_id),
              KEY idx_cs_time      (occurred_at),
              KEY idx_cs_item      (item_key, occurred_at),
              KEY idx_cs_firm      (shop_firm_id, occurred_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """,
            """
            CREATE TABLE chestshop_shop (
              shop_id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
              world            VARCHAR(64)  NOT NULL,
              sign_x INT NOT NULL, sign_y INT NOT NULL, sign_z INT NOT NULL,
              is_admin_shop       TINYINT(1)   NOT NULL DEFAULT 0,
              shop_account_id     INT UNSIGNED NULL,
              shop_account_type   ENUM('PERSONAL','BUSINESS','GOVERNMENT','SYSTEM') NULL,
              shop_firm_id        INT          NULL,
              shop_owner_uuid_bin BINARY(16)   NULL,
              material         VARCHAR(64)  NOT NULL,
              item_key         VARCHAR(190) NOT NULL,
              item_name        VARCHAR(255) NOT NULL,
              item_custom      TINYINT(1)   NOT NULL DEFAULT 0,
              item_data        MEDIUMTEXT   NULL,
              buy_price        DECIMAL(19,2) NULL,
              sell_price       DECIMAL(19,2) NULL,
              batch_qty        INT          NOT NULL,
              current_stock    INT          NULL,
              active           TINYINT(1)   NOT NULL DEFAULT 1,
              created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
              stock_at         TIMESTAMP    NULL,
              last_seen        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              PRIMARY KEY (shop_id),
              UNIQUE KEY uq_shop_location (world, sign_x, sign_y, sign_z),
              KEY idx_shop_item   (item_key, active),
              KEY idx_shop_mat    (material, active),
              KEY idx_shop_firm   (shop_firm_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """,
            // Consolidated per-account access (PAR-249) — backs MembershipMapper.isMember,
            // which gates non-owner transaction-history reads.
            """
            CREATE TABLE account_access (
              account_id        INT UNSIGNED NOT NULL,
              subject_uuid_bin  BINARY(16)   NOT NULL,
              level             ENUM('VIEWER','MEMBER','AUTHORIZER') NOT NULL,
              added_by_uuid_bin BINARY(16)   NOT NULL,
              created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
              removed_at        TIMESTAMP    NULL,
              PRIMARY KEY (account_id, subject_uuid_bin),
              KEY idx_access_active (account_id, removed_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """,
            // ADT-13 single-source read-access view (mirrors V22). isMember reads this
            // rather than account_access directly. Only the API view is needed here;
            // the explorer's more-permissive web view lives in its own test schema.
            """
            CREATE VIEW account_read_access_api AS
            SELECT account_id, subject_uuid_bin
              FROM account_access
             WHERE level IN ('MEMBER','AUTHORIZER')
               AND removed_at IS NULL
            """,
            // account_balances view (mirrors V1) — AccountService.getBalance reads it.
            """
            CREATE OR REPLACE VIEW account_balances AS
            SELECT a.account_id, COALESCE(abm.balance, 0.00) AS balance
              FROM accounts a
              LEFT JOIN account_balances_mat abm ON abm.account_id = a.account_id
            """,
            // Firm roster tables (mirror V1) — FirmService.listEmployees/listRoles read them.
            """
            CREATE TABLE firm_role (
              role_id            INT         NOT NULL AUTO_INCREMENT,
              firm_id            INT         NOT NULL,
              name               VARCHAR(64) NOT NULL,
              rank_order         INT         NOT NULL,
              is_proprietor_like TINYINT(1)  NOT NULL DEFAULT 0,
              is_default         TINYINT(1)  NOT NULL DEFAULT 0,
              deleted_at         TIMESTAMP   NULL,
              PRIMARY KEY (role_id),
              KEY idx_role_active (firm_id, deleted_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """,
            """
            CREATE TABLE firm_role_permission (
              role_id    INT NOT NULL,
              permission ENUM('ADMIN','FINANCIAL','CHESTSHOP','DEFAULT') NOT NULL,
              deleted_at TIMESTAMP NULL,
              PRIMARY KEY (role_id, permission)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """,
            """
            CREATE TABLE firm_employee (
              firm_id         INT        NOT NULL,
              player_uuid_bin BINARY(16) NOT NULL,
              role_id         INT        NOT NULL,
              joined_at       TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
              left_at         TIMESTAMP  NULL,
              PRIMARY KEY (firm_id, player_uuid_bin, joined_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """,
            // api_keys (mirrors V1 + V15 SERVICE scope + V23 nullable token). AuthService
            // rotation and AdminApiKeyService revoke/rotate exercise this table.
            """
            CREATE TABLE api_keys (
              key_id         INT UNSIGNED NOT NULL AUTO_INCREMENT,
              key_type       ENUM('PERSONAL','BUSINESS','GOVERNMENT','SERVICE') NOT NULL DEFAULT 'PERSONAL',
              account_id     INT UNSIGNED NULL,
              firm_id        INT          NULL,
              owner_uuid_bin BINARY(16)   NOT NULL,
              jwt_id         CHAR(36)     NOT NULL,
              token          TEXT         NULL,
              issued_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
              expires_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
              revoked        TINYINT(1)   NOT NULL DEFAULT 0,
              PRIMARY KEY (key_id),
              UNIQUE KEY uq_api_key_jwt_id (jwt_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """,
            // Per-issuer rate-limit overrides (mirrors V5).
            """
            CREATE TABLE api_rate_limit_override (
              owner_uuid_bin BINARY(16)   NOT NULL,
              multiplier     DECIMAL(6,2) NOT NULL DEFAULT 1.00,
              note           VARCHAR(255) NULL,
              updated_by_bin BINARY(16)   NULL,
              updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              PRIMARY KEY (owner_uuid_bin)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """,
            // Webhook tables (mirror V13; api_key_id nullable per V14). FKs omitted to
            // match this standin schema's style and keep truncateAll() order-free.
            """
            CREATE TABLE webhook_subscription (
              subscription_id      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
              api_key_id           INT UNSIGNED    NULL,
              owner_uuid_bin       BINARY(16)      NOT NULL,
              key_type             ENUM('PERSONAL','BUSINESS','GOVERNMENT') NOT NULL,
              account_id           INT UNSIGNED    NULL,
              firm_id              INT             NULL,
              target_url           VARCHAR(2048)   NOT NULL,
              secret               CHAR(64)        NOT NULL,
              active               TINYINT(1)      NOT NULL DEFAULT 1,
              consecutive_failures INT UNSIGNED    NOT NULL DEFAULT 0,
              disabled_at          TIMESTAMP       NULL,
              created_at           TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at           TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              PRIMARY KEY (subscription_id),
              KEY idx_websub_active_account (active, account_id),
              KEY idx_websub_active_firm (active, firm_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """,
            """
            CREATE TABLE webhook_delivery (
              delivery_id     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
              subscription_id BIGINT UNSIGNED NOT NULL,
              txn_id          BIGINT UNSIGNED NOT NULL,
              account_id      INT UNSIGNED    NOT NULL,
              status          ENUM('PENDING','DELIVERED','FAILED') NOT NULL DEFAULT 'PENDING',
              attempts        INT UNSIGNED    NOT NULL DEFAULT 0,
              http_status     INT             NULL,
              last_error      VARCHAR(255)    NULL,
              next_attempt_at TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
              created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              PRIMARY KEY (delivery_id),
              UNIQUE KEY uq_delivery_sub_txn (subscription_id, txn_id),
              KEY idx_delivery_due (status, next_attempt_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """,
            """
            CREATE TABLE webhook_cursor (
              id                     TINYINT UNSIGNED NOT NULL DEFAULT 1,
              last_dispatched_txn_id BIGINT UNSIGNED  NOT NULL DEFAULT 0,
              updated_at             TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              PRIMARY KEY (id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """,
    };
}
