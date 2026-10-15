-- =============================================================================
-- DELIBERATE LIGHT STAND-IN — NOT the authoritative schema (economy-explorer/testing/0005).
-- =============================================================================
-- This file is a hand-maintained, intentionally *light* subset of the economy
-- schema, kept here so the in-repo integration tests (test/integration/sql.test.ts,
-- run only with RUN_INTEGRATION=1) can exercise the real lib/sql queries against a
-- real MariaDB quickly, with no Flyway/JVM bootstrap.
--
-- It is DELIBERATELY NOT wired to Flyway. The design decision (0005) is:
--   * in-repo tests stay LIGHT  — unit + light integration + regression;
--   * the FULL authoritative-schema harness (all migrations, DB triggers such as
--     trg_postings_ai, generated columns, constraints) lives OUTSIDE the monorepo
--     in ../other. Trigger/full-schema behaviour is covered there, not here.
--
-- The AUTHORITATIVE schema is economy-flyway/ (V<n>__*.sql migrations). This file
-- only needs enough shape (tables/columns/views the queries touch) to run those
-- queries; it does not — and is not meant to — reproduce triggers or every column.
--
-- DRIFT GUARD — IMPORTANT: the .github/workflows/economy-explorer-schema-drift.yml
-- gate does NOT guard this file. That gate regenerates lib/db.generated.ts (the
-- kysely types) from the migrated Flyway schema and fails on drift there. This
-- snapshot is unguarded: if a migration changes a shape a query here relies on,
-- keep this file in sync by hand (or the affected integration test will fail).
-- =============================================================================

SET FOREIGN_KEY_CHECKS=0;

CREATE TABLE `accounts` (
  `account_id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `account_type` enum('PERSONAL','BUSINESS','GOVERNMENT','SYSTEM') NOT NULL,
  `owner_uuid_bin` binary(16) NOT NULL,
  `display_name` varchar(255) DEFAULT NULL,
  `requires_authorization` tinyint(1) NOT NULL DEFAULT 0,
  `is_archived` tinyint(1) NOT NULL DEFAULT 0,
  `allow_overdraft` tinyint(1) NOT NULL DEFAULT 0,
  `credit_limit` decimal(19,2) NOT NULL DEFAULT 0.00,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `_personal_owner` binary(16) GENERATED ALWAYS AS (case when `account_type` = 'PERSONAL' then `owner_uuid_bin` end) VIRTUAL,
  PRIMARY KEY (`account_id`),
  UNIQUE KEY `uq_one_personal_per_player` (`_personal_owner`),
  KEY `idx_accounts_owner` (`owner_uuid_bin`),
  KEY `idx_accounts_type` (`account_type`),
  KEY `idx_accounts_type_name` (`account_type`,`display_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `account_balances_mat` (
  `account_id` int(10) unsigned NOT NULL,
  `balance` decimal(19,2) NOT NULL DEFAULT 0.00,
  `version` bigint(20) NOT NULL DEFAULT 0,
  PRIMARY KEY (`account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Consolidated per-account access (PAR-249): one row per (account, subject) on
-- the ordered scale VIEWER < MEMBER < AUTHORIZER.
CREATE TABLE `account_access` (
  `account_id` int(10) unsigned NOT NULL,
  `subject_uuid_bin` binary(16) NOT NULL,
  `level` enum('VIEWER','MEMBER','AUTHORIZER') NOT NULL,
  `added_by_uuid_bin` binary(16) NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `removed_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`account_id`,`subject_uuid_bin`),
  KEY `idx_access_subject` (`subject_uuid_bin`),
  KEY `idx_access_active` (`account_id`,`removed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `account_group_access` (
  `account_id` int(10) unsigned NOT NULL,
  `lp_group` varchar(64) NOT NULL,
  `level` enum('VIEWER','MEMBER','AUTHORIZER') NOT NULL,
  `added_by_uuid_bin` binary(16) NOT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT current_timestamp(3),
  `removed_at` timestamp(3) NULL DEFAULT NULL,
  PRIMARY KEY (`account_id`,`lp_group`),
  KEY `idx_gaccess_active` (`account_id`,`removed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `ledger_txns` (
  `txn_id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `trade_time` timestamp NOT NULL DEFAULT current_timestamp(),
  `settlement_time` timestamp NOT NULL DEFAULT current_timestamp(),
  `message` varchar(255) NOT NULL,
  `initiator_uuid_bin` binary(16) NOT NULL,
  `authorizer_uuid_bin` binary(16) DEFAULT NULL,
  `plugin_system` varchar(64) DEFAULT NULL,
  `client_dedup_key` binary(32) DEFAULT NULL,
  PRIMARY KEY (`txn_id`),
  UNIQUE KEY `uq_ledger_dedup` (`client_dedup_key`),
  KEY `idx_ledger_settle_time` (`settlement_time`),
  KEY `idx_ledger_initiator` (`initiator_uuid_bin`),
  KEY `idx_ledger_authorizer` (`authorizer_uuid_bin`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `ledger_postings` (
  `posting_id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `txn_id` bigint(20) unsigned NOT NULL,
  `account_id` int(10) unsigned NOT NULL,
  `amount` decimal(19,2) NOT NULL,
  `memo` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`posting_id`),
  KEY `idx_postings_txn` (`txn_id`),
  KEY `idx_postings_account` (`account_id`),
  KEY `idx_postings_account_txn_amount` (`account_id`,`txn_id`,`amount`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `firm` (
  `firm_id` int(11) NOT NULL AUTO_INCREMENT,
  `display_name` varchar(255) NOT NULL,
  `proprietor_uuid_bin` binary(16) NOT NULL,
  `discord_url` varchar(255) DEFAULT NULL,
  `hq_region` varchar(64) DEFAULT NULL,
  `default_account_id` int(10) unsigned DEFAULT NULL,
  `is_archived` tinyint(1) NOT NULL DEFAULT 0,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`firm_id`),
  `name_released` tinyint(1) NOT NULL DEFAULT 0,
  `reserved_display_name` varchar(255) COLLATE utf8mb4_unicode_ci GENERATED ALWAYS AS
    (CASE WHEN `is_archived` = 1 AND `name_released` = 1 THEN NULL ELSE `display_name` END) STORED,
  UNIQUE KEY `uq_firm_reserved_display_name` (`reserved_display_name`),
  KEY `idx_firm_display_name` (`display_name`),
  UNIQUE KEY `uq_firm_default_account_id` (`default_account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `firm_properties` (
  `firm_id` int(11) NOT NULL,
  `key` varchar(128) NOT NULL,
  `value` text NOT NULL,
  `type` enum('STRING','INTEGER','BIGDECIMAL','BOOLEAN') NOT NULL,
  `deleted_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`firm_id`,`key`),
  KEY `idx_fp_active` (`firm_id`,`deleted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `firm_accounts` (
  `firm_id` int(11) NOT NULL,
  `account_id` int(10) unsigned NOT NULL,
  `added_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `removed_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`firm_id`,`account_id`),
  UNIQUE KEY `uq_account_once` (`account_id`),
  KEY `idx_firm_account_active` (`firm_id`,`removed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `firm_employee` (
  `firm_id` int(11) NOT NULL,
  `player_uuid_bin` binary(16) NOT NULL,
  `role_id` int(11) NOT NULL,
  `joined_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `left_at` timestamp NULL DEFAULT NULL,
  `added_by_uuid_bin` binary(16) DEFAULT NULL,
  `removed_by_uuid_bin` binary(16) DEFAULT NULL,
  `is_current` tinyint(1) GENERATED ALWAYS AS (`left_at` is null) VIRTUAL,
  PRIMARY KEY (`firm_id`,`player_uuid_bin`,`joined_at`),
  UNIQUE KEY `uq_emp_one_current` (`firm_id`,`player_uuid_bin`,`is_current`),
  KEY `idx_emp_current` (`firm_id`,`is_current`,`role_id`),
  KEY `idx_emp_player` (`player_uuid_bin`,`is_current`),
  KEY `fk_emp_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `firm_role` (
  `role_id` int(11) NOT NULL AUTO_INCREMENT,
  `firm_id` int(11) NOT NULL,
  `name` varchar(64) NOT NULL,
  `rank_order` int(11) NOT NULL,
  `is_proprietor_like` tinyint(1) NOT NULL DEFAULT 0,
  `is_default` tinyint(1) NOT NULL DEFAULT 0,
  `deleted_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`role_id`),
  UNIQUE KEY `uq_role_name` (`firm_id`,`name`),
  UNIQUE KEY `uq_role_rank` (`firm_id`,`rank_order`),
  KEY `idx_role_active` (`firm_id`,`deleted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `firm_role_permission` (
  `role_id` int(11) NOT NULL,
  `permission` enum('ADMIN','FINANCIAL','CHESTSHOP','DEFAULT') NOT NULL,
  `deleted_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`role_id`,`permission`),
  KEY `idx_rp_active` (`role_id`,`deleted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `economy_players` (
  `player_uuid_bin` binary(16) NOT NULL,
  `current_name` varchar(32) NOT NULL,
  `name_lower` varchar(32) GENERATED ALWAYS AS (lcase(`current_name`)) VIRTUAL,
  `first_seen` timestamp NOT NULL DEFAULT current_timestamp(),
  `last_seen` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  `last_login_epoch` bigint(20) DEFAULT NULL,
  PRIMARY KEY (`player_uuid_bin`),
  UNIQUE KEY `uq_economy_players_name` (`name_lower`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `chestshop_sale` (
  `sale_id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `txn_id` bigint(20) unsigned DEFAULT NULL,
  `occurred_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `direction` enum('BUY','SELL') NOT NULL,
  `customer_uuid_bin` binary(16) NOT NULL,
  `shop_account_id` int(10) unsigned DEFAULT NULL,
  `shop_account_type` enum('PERSONAL','BUSINESS','GOVERNMENT','SYSTEM') DEFAULT NULL,
  `shop_firm_id` int(11) DEFAULT NULL,
  `shop_owner_uuid_bin` binary(16) DEFAULT NULL,
  `is_admin_shop` tinyint(1) NOT NULL DEFAULT 0,
  `material` varchar(64) NOT NULL,
  `item_key` varchar(190) NOT NULL,
  `item_name` varchar(255) NOT NULL,
  `item_custom` tinyint(1) NOT NULL DEFAULT 0,
  `item_data` mediumtext DEFAULT NULL,
  `quantity` int(11) NOT NULL,
  `unit_price` decimal(19,4) NOT NULL,
  `total_price` decimal(19,2) NOT NULL,
  `tax_amount` decimal(19,2) NOT NULL DEFAULT 0.00,
  `world` varchar(64) DEFAULT NULL,
  `sign_x` int(11) DEFAULT NULL,
  `sign_y` int(11) DEFAULT NULL,
  `sign_z` int(11) DEFAULT NULL,
  `shop_stock` int(11) DEFAULT NULL,
  PRIMARY KEY (`sale_id`),
  KEY `idx_cs_time` (`occurred_at`),
  KEY `idx_cs_material` (`material`,`occurred_at`),
  KEY `idx_cs_item` (`item_key`,`occurred_at`),
  KEY `idx_cs_acct` (`shop_account_id`,`occurred_at`),
  KEY `idx_cs_firm` (`shop_firm_id`,`occurred_at`),
  KEY `idx_cs_owner` (`shop_owner_uuid_bin`,`occurred_at`),
  KEY `idx_cs_customer` (`customer_uuid_bin`,`occurred_at`),
  KEY `idx_cs_txn` (`txn_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `chestshop_shop` (
  `shop_id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `world` varchar(64) NOT NULL,
  `sign_x` int(11) NOT NULL,
  `sign_y` int(11) NOT NULL,
  `sign_z` int(11) NOT NULL,
  `is_admin_shop` tinyint(1) NOT NULL DEFAULT 0,
  `shop_account_id` int(10) unsigned DEFAULT NULL,
  `shop_account_type` enum('PERSONAL','BUSINESS','GOVERNMENT','SYSTEM') DEFAULT NULL,
  `shop_firm_id` int(11) DEFAULT NULL,
  `shop_owner_uuid_bin` binary(16) DEFAULT NULL,
  `material` varchar(64) NOT NULL,
  `item_key` varchar(190) NOT NULL,
  `item_name` varchar(255) NOT NULL,
  `item_custom` tinyint(1) NOT NULL DEFAULT 0,
  `item_data` mediumtext DEFAULT NULL,
  `buy_price` decimal(19,2) DEFAULT NULL,
  `sell_price` decimal(19,2) DEFAULT NULL,
  `batch_qty` int(11) NOT NULL,
  `current_stock` int(11) DEFAULT NULL,
  `active` tinyint(1) NOT NULL DEFAULT 1,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `stock_at` timestamp NULL DEFAULT NULL,
  `last_seen` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`shop_id`),
  UNIQUE KEY `uq_shop_location` (`world`,`sign_x`,`sign_y`,`sign_z`),
  KEY `idx_shop_item` (`item_key`,`active`),
  KEY `idx_shop_mat` (`material`,`active`),
  KEY `idx_shop_firm` (`shop_firm_id`),
  KEY `idx_shop_owner` (`shop_owner_uuid_bin`),
  KEY `idx_shop_acct` (`shop_account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `government_fines` (
  `fine_id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `player_uuid_bin` binary(16) NOT NULL,
  `gov_account_id` int(10) unsigned NOT NULL,
  `amount` decimal(19,2) NOT NULL,
  `reason` varchar(255) NOT NULL,
  `txn_id` bigint(20) unsigned NOT NULL,
  `issued_by_uuid_bin` binary(16) NOT NULL,
  `issued_at` timestamp(3) NOT NULL DEFAULT current_timestamp(3),
  `revoked` tinyint(1) NOT NULL DEFAULT 0,
  `revoked_by_uuid_bin` binary(16) DEFAULT NULL,
  `revoke_txn_id` bigint(20) unsigned DEFAULT NULL,
  `revoked_at` timestamp(3) NULL DEFAULT NULL,
  PRIMARY KEY (`fine_id`),
  KEY `idx_fines_player` (`player_uuid_bin`),
  KEY `fk_fines_gov_account` (`gov_account_id`),
  KEY `fk_fines_txn` (`txn_id`),
  KEY `fk_fines_revoke_txn` (`revoke_txn_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `api_keys` (
  `key_id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `key_type` enum('PERSONAL','BUSINESS','GOVERNMENT') NOT NULL DEFAULT 'PERSONAL',
  `account_id` int(10) unsigned DEFAULT NULL,
  `firm_id` int(11) DEFAULT NULL,
  `owner_uuid_bin` binary(16) NOT NULL,
  `jwt_id` char(36) NOT NULL COMMENT 'jti claim — updated on reissue',
  `token` text NOT NULL COMMENT 'Full signed JWT',
  `issued_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `expires_at` timestamp NOT NULL,
  `revoked` tinyint(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`key_id`),
  UNIQUE KEY `uq_api_key_jwt_id` (`jwt_id`),
  KEY `idx_api_key_account` (`account_id`),
  KEY `idx_api_key_owner` (`owner_uuid_bin`),
  KEY `idx_api_key_type` (`key_type`),
  KEY `idx_api_key_firm` (`firm_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `api_rate_limit_override` (
  `owner_uuid_bin` binary(16) NOT NULL,
  `multiplier` decimal(6,2) NOT NULL DEFAULT 1.00,
  `note` varchar(255) DEFAULT NULL,
  `updated_by_bin` binary(16) DEFAULT NULL COMMENT 'explorer admin (player UUID) who last changed it',
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`owner_uuid_bin`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `explorer_audit` (
  `audit_id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `at` timestamp NOT NULL DEFAULT current_timestamp(),
  `actor_sub` varchar(64) NOT NULL,
  `actor_uuid_bin` binary(16) DEFAULT NULL,
  `actor_name` varchar(32) DEFAULT NULL,
  `actor_role` varchar(32) DEFAULT NULL,
  `method` varchar(8) NOT NULL,
  `path` varchar(255) NOT NULL,
  `target_type` varchar(32) DEFAULT NULL,
  `target_id` varchar(64) DEFAULT NULL,
  `outcome` smallint(6) NOT NULL,
  `source_ip` varchar(45) DEFAULT NULL,
  PRIMARY KEY (`audit_id`),
  KEY `idx_audit_actor` (`actor_uuid_bin`,`at`),
  KEY `idx_audit_target` (`target_type`,`target_id`,`at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `explorer_link_code` (
  `code` char(8) NOT NULL,
  `keycloak_sub` varchar(64) NOT NULL,
  `minecraft_name` varchar(32) DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `expires_at` timestamp NOT NULL,
  PRIMARY KEY (`code`),
  KEY `idx_link_code_expiry` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `explorer_identity` (
  `keycloak_sub` varchar(64) NOT NULL,
  `player_uuid_bin` binary(16) NOT NULL,
  `minecraft_name` varchar(32) DEFAULT NULL,
  `linked_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `linked_by` varchar(64) DEFAULT NULL,
  PRIMARY KEY (`keycloak_sub`),
  KEY `idx_identity_player` (`player_uuid_bin`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `explorer_role` (
  `player_uuid_bin` binary(16) NOT NULL,
  `role` varchar(32) NOT NULL,
  `granted_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `granted_by_uuid_bin` binary(16) DEFAULT NULL,
  PRIMARY KEY (`player_uuid_bin`,`role`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `explorer_group` (
  `group_id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(64) NOT NULL,
  `description` varchar(255) DEFAULT NULL,
  `luckperms_node` varchar(128) DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `created_by_uuid_bin` binary(16) DEFAULT NULL,
  PRIMARY KEY (`group_id`),
  UNIQUE KEY `uq_group_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `explorer_group_capability` (
  `group_id` int(10) unsigned NOT NULL,
  `capability` varchar(64) NOT NULL,
  PRIMARY KEY (`group_id`,`capability`),
  CONSTRAINT fk_test_group_role_group FOREIGN KEY (group_id) REFERENCES explorer_group(group_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `explorer_group_member` (
  `group_id` int(10) unsigned NOT NULL,
  `player_uuid_bin` binary(16) NOT NULL,
  `source` enum('manual','luckperms') NOT NULL DEFAULT 'manual',
  `added_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `added_by_uuid_bin` binary(16) DEFAULT NULL,
  PRIMARY KEY (`group_id`,`player_uuid_bin`,`source`),
  KEY `idx_group_member_player` (`player_uuid_bin`),
  CONSTRAINT fk_test_group_member_group FOREIGN KEY (group_id) REFERENCES explorer_group(group_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ADT-13 single-source read-access views (mirror V22__account_read_access_views.sql).
CREATE OR REPLACE VIEW `account_read_access_api` AS
  SELECT account_id, subject_uuid_bin FROM account_access
   WHERE level IN ('MEMBER','AUTHORIZER') AND removed_at IS NULL;

CREATE OR REPLACE VIEW `account_read_access_web` AS
  SELECT account_id, subject_uuid_bin FROM account_access
   WHERE level IN ('VIEWER','MEMBER','AUTHORIZER') AND removed_at IS NULL;

SET FOREIGN_KEY_CHECKS=1;

ALTER TABLE explorer_group_member ADD COLUMN synced_at TIMESTAMP NULL;
CREATE TABLE explorer_lp_group_member (
 lp_group VARCHAR(64) NOT NULL, player_uuid_bin BINARY(16) NOT NULL,
 synced_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(lp_group, player_uuid_bin)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- One firm-finance relationship rule for firm pages and all account consumers.
CREATE OR REPLACE VIEW firm_financial_access_web AS
SELECT firm_id, proprietor_uuid_bin AS subject_uuid_bin FROM firm
UNION
SELECT fe.firm_id, fe.player_uuid_bin FROM firm_employee fe
JOIN firm_role fr ON fr.role_id = fe.role_id AND fr.firm_id = fe.firm_id AND fr.deleted_at IS NULL
JOIN firm_role_permission rp ON rp.role_id = fr.role_id AND rp.deleted_at IS NULL
WHERE fe.left_at IS NULL AND rp.permission IN ('FINANCIAL', 'ADMIN');

-- Shared relationship rule used by account pages, government and My data.
-- A group relationship grants read access only; API spending/key scopes are unchanged.
CREATE OR REPLACE VIEW account_read_access_web AS
SELECT account_id, subject_uuid_bin FROM account_access
WHERE level IN ('VIEWER', 'MEMBER', 'AUTHORIZER') AND removed_at IS NULL
UNION
SELECT aga.account_id, gm.player_uuid_bin FROM account_group_access aga
JOIN explorer_lp_group_member gm ON gm.lp_group = aga.lp_group
WHERE aga.removed_at IS NULL AND gm.synced_at > CURRENT_TIMESTAMP - INTERVAL 1 HOUR
UNION
SELECT fa.account_id, ff.subject_uuid_bin FROM firm_accounts fa
JOIN firm_financial_access_web ff ON ff.firm_id = fa.firm_id;

-- economy-flyway V32: plugin config published for display (PAR-80).
CREATE TABLE `economy_config` (
  `config_key` varchar(128) NOT NULL,
  `owner` varchar(32) NOT NULL,
  `value_type` enum('BOOLEAN','DECIMAL','JSON') NOT NULL,
  `config_value` text NOT NULL,
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
