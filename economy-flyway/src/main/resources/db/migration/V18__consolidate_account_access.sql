-- =====================================================================
-- CONSOLIDATE ACCOUNT ACCESS  (Treasury)  PAR-249
--
-- The per-account access tiers were six tables — account_members /
-- account_authorizers / account_viewers plus a LuckPerms-group variant of each —
-- for what is really one ordered capability scale:
--
--   VIEWER (read) < MEMBER (read + spend) < AUTHORIZER (read + spend + manage)
--
-- An authorizer was already required to be a member (composite FK), confirming the
-- linear nesting — "authorizer" is just "member + can-manage-access". Collapse to
-- one row per (account, subject) carrying the highest level. MySQL ENUM compares by
-- declaration order, so the level ordering below is meaningful for `>=` checks.
--
-- account_viewers / account_group_viewers were introduced one migration earlier
-- (PAR-237) but never reached a persistent environment, so there is nothing to
-- migrate out of them — they're simply not created.
--
-- PROD SHIM: economy-explorer was deployed ahead of this migration and bridged
-- with a read-only compat VIEW named account_access (PAR-250). Drop it first so
-- the CREATE TABLE below doesn't collide with an existing object. IF EXISTS makes
-- this a harmless no-op in any environment where the shim was never applied
-- (fresh installs, CI, dev), so the migration is self-contained — no manual step.
--
-- NOTE ON THE DROP VIEW EDIT: the two DROP VIEW lines were added after V18 was first
-- committed. That is normally a Flyway immutability violation (editing an applied
-- migration trips a checksum mismatch), but it is safe here: the live DB is at V15
-- (verified) so V18 has never been applied to any persistent history, and there is a
-- single production database. The DROP VIEW must stay *inside* V18, before the CREATE
-- TABLE — the prod shim is a VIEW named account_access, so the drop has to run ahead
-- of this table creation. Moving it to a later migration would run it after the table
-- already failed to create. Do not relocate it.
-- =====================================================================

DROP VIEW IF EXISTS account_access;
DROP VIEW IF EXISTS account_group_access;

CREATE TABLE account_access (
    account_id        INT UNSIGNED NOT NULL,
    subject_uuid_bin  BINARY(16)   NOT NULL,
    level             ENUM('VIEWER','MEMBER','AUTHORIZER') NOT NULL,
    added_by_uuid_bin BINARY(16)   NOT NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    removed_at        TIMESTAMP    NULL,
    PRIMARY KEY (account_id, subject_uuid_bin),
    KEY idx_access_subject (subject_uuid_bin),
    KEY idx_access_active (account_id, removed_at),
    CONSTRAINT fk_access_account FOREIGN KEY (account_id)
        REFERENCES accounts(account_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE account_group_access (
    account_id        INT UNSIGNED  NOT NULL,
    lp_group          VARCHAR(64)   NOT NULL,
    level             ENUM('VIEWER','MEMBER','AUTHORIZER') NOT NULL,
    added_by_uuid_bin BINARY(16)    NOT NULL,
    created_at        TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    removed_at        TIMESTAMP(3)  NULL,
    PRIMARY KEY (account_id, lp_group),
    KEY idx_gaccess_active (account_id, removed_at),
    CONSTRAINT fk_gaccess_account FOREIGN KEY (account_id)
        REFERENCES accounts(account_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---- Backfill from the legacy tables ----
-- Seed everyone who has a member row at MEMBER (carrying their active/inactive
-- state via removed_at), then promote the active authorizers to AUTHORIZER. An
-- authorizer always has a member row (old composite FK), so no orphans.
INSERT INTO account_access (account_id, subject_uuid_bin, level, added_by_uuid_bin, created_at, removed_at)
SELECT account_id, member_uuid_bin, 'MEMBER', added_by_uuid_bin, created_at, left_at
FROM account_members;

UPDATE account_access aa
JOIN account_authorizers au
  ON au.account_id = aa.account_id
 AND au.authorizer_uuid_bin = aa.subject_uuid_bin
 AND au.revoked_at IS NULL          -- only a still-active authorizer raises the level
SET aa.level = 'AUTHORIZER';

INSERT INTO account_group_access (account_id, lp_group, level, added_by_uuid_bin, created_at, removed_at)
SELECT account_id, lp_group, 'MEMBER', added_by_uuid_bin, created_at, left_at
FROM account_group_members;

UPDATE account_group_access aga
JOIN account_group_authorizers ga
  ON ga.account_id = aga.account_id
 AND ga.lp_group = aga.lp_group
 AND ga.revoked_at IS NULL
SET aga.level = 'AUTHORIZER';

-- ---- Drop the legacy tables (authorizers first — FK into members) ----
DROP TABLE account_authorizers;
DROP TABLE account_members;
DROP TABLE account_group_authorizers;
DROP TABLE account_group_members;
