-- PAR-501: one namespace across every writer. Requires writers stopped during
-- migration. Preflight refuses collisions; never select a winner or rewrite IDs,
-- owners, names, balances or ledger. See wiki/name-namespace.md before rollout.
-- MariaDB DDL commits implicitly: on preflight failure only the guard routine
-- may remain; inspect/repair failed Flyway history before a deliberate retry.
DROP PROCEDURE IF EXISTS global_names_preflight;
CREATE PROCEDURE global_names_preflight()
BEGIN
    -- Existing display spelling is never rewritten by a migration. Whitespace
    -- cleanup must be reviewed just like a collision before this can apply.
    IF EXISTS (SELECT 1 FROM accounts WHERE BINARY display_name<>BINARY TRIM(display_name))
       OR EXISTS (SELECT 1 FROM firm WHERE BINARY display_name<>BINARY TRIM(display_name))
       OR EXISTS (SELECT 1 FROM economy_players WHERE BINARY current_name<>BINARY TRIM(current_name)) THEN
        SIGNAL SQLSTATE '45000' SET MYSQL_ERRNO=1644,
          MESSAGE_TEXT='PAR-501: surrounding whitespace in existing names; approve cleanup before migration';
    END IF;
    IF EXISTS (SELECT 1 FROM (
SELECT CONVERT('ACCOUNT' USING utf8mb4) COLLATE utf8mb4_unicode_ci AS source_kind,
       CONVERT(CAST(account_id AS CHAR) USING utf8mb4) COLLATE utf8mb4_unicode_ci AS subject_id,
       CONVERT(TRIM(display_name) USING utf8mb4) COLLATE utf8mb4_unicode_ci AS name_key,
       CONVERT(CASE WHEN account_type='PERSONAL' THEN CONCAT('P:',HEX(owner_uuid_bin)) ELSE CONCAT('A:',account_id) END USING utf8mb4) COLLATE utf8mb4_unicode_ci AS entity_key
FROM accounts WHERE display_name IS NOT NULL
UNION ALL
SELECT CONVERT('FIRM' USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(CAST(firm_id AS CHAR) USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(TRIM(display_name) USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(CONCAT('F:',firm_id) USING utf8mb4) COLLATE utf8mb4_unicode_ci
FROM firm WHERE NOT (is_archived=1 AND name_released=1)
UNION ALL
SELECT CONVERT('PLAYER' USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(HEX(player_uuid_bin) USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(TRIM(current_name) USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(CONCAT('P:',HEX(player_uuid_bin)) USING utf8mb4) COLLATE utf8mb4_unicode_ci
FROM economy_players WHERE current_name <> HEX(player_uuid_bin)
UNION ALL
SELECT CONVERT('ALIAS' USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(alias USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(TRIM(alias) USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(CONCAT('A:',account_id) USING utf8mb4) COLLATE utf8mb4_unicode_ci
FROM firm_shop_alias_reservations
UNION ALL
SELECT CONVERT('CURRENT_ALIAS' USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(CAST(account_id AS CHAR) USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(TRIM(shop_alias) USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(CONCAT('A:',account_id) USING utf8mb4) COLLATE utf8mb4_unicode_ci
FROM firm_accounts WHERE shop_alias IS NOT NULL
    ) c GROUP BY name_key HAVING COUNT(DISTINCT entity_key)>1 OR name_key='' ) THEN
        SIGNAL SQLSTATE '45000' SET MYSQL_ERRNO=1644,
          MESSAGE_TEXT='PAR-501: unresolved global name collisions or blank names; audit and approve remediation before migration';
    END IF;
END;
CALL global_names_preflight();
DROP PROCEDURE global_names_preflight;

CREATE TABLE global_name_reservations (
    name_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
    entity_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    claim_count INT UNSIGNED NOT NULL,
    PRIMARY KEY (name_key),
    CONSTRAINT ck_global_name_claim_count CHECK (claim_count > 0)
) ENGINE=InnoDB;
CREATE TABLE global_name_claims (
    source_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subject_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
    entity_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (source_kind, subject_id),
    KEY idx_global_claim_name (name_key),
    CONSTRAINT fk_global_claim_name FOREIGN KEY (name_key)
        REFERENCES global_name_reservations(name_key) ON DELETE RESTRICT
) ENGINE=InnoDB;

INSERT INTO global_name_reservations (name_key,entity_key,claim_count)
SELECT name_key,MIN(entity_key),COUNT(*) FROM (
SELECT CONVERT('ACCOUNT' USING utf8mb4) COLLATE utf8mb4_unicode_ci AS source_kind,
       CONVERT(CAST(account_id AS CHAR) USING utf8mb4) COLLATE utf8mb4_unicode_ci AS subject_id,
       CONVERT(TRIM(display_name) USING utf8mb4) COLLATE utf8mb4_unicode_ci AS name_key,
       CONVERT(CASE WHEN account_type='PERSONAL' THEN CONCAT('P:',HEX(owner_uuid_bin)) ELSE CONCAT('A:',account_id) END USING utf8mb4) COLLATE utf8mb4_unicode_ci AS entity_key
FROM accounts WHERE display_name IS NOT NULL
UNION ALL
SELECT CONVERT('FIRM' USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(CAST(firm_id AS CHAR) USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(TRIM(display_name) USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(CONCAT('F:',firm_id) USING utf8mb4) COLLATE utf8mb4_unicode_ci
FROM firm WHERE NOT (is_archived=1 AND name_released=1)
UNION ALL
SELECT CONVERT('PLAYER' USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(HEX(player_uuid_bin) USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(TRIM(current_name) USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(CONCAT('P:',HEX(player_uuid_bin)) USING utf8mb4) COLLATE utf8mb4_unicode_ci
FROM economy_players WHERE current_name <> HEX(player_uuid_bin)
UNION ALL
SELECT CONVERT('ALIAS' USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(alias USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(TRIM(alias) USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(CONCAT('A:',account_id) USING utf8mb4) COLLATE utf8mb4_unicode_ci
FROM firm_shop_alias_reservations
UNION ALL
SELECT CONVERT('CURRENT_ALIAS' USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(CAST(account_id AS CHAR) USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(TRIM(shop_alias) USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(CONCAT('A:',account_id) USING utf8mb4) COLLATE utf8mb4_unicode_ci
FROM firm_accounts WHERE shop_alias IS NOT NULL
) c GROUP BY name_key;
INSERT INTO global_name_claims (source_kind,subject_id,name_key,entity_key)
SELECT CONVERT('ACCOUNT' USING utf8mb4) COLLATE utf8mb4_unicode_ci AS source_kind,
       CONVERT(CAST(account_id AS CHAR) USING utf8mb4) COLLATE utf8mb4_unicode_ci AS subject_id,
       CONVERT(TRIM(display_name) USING utf8mb4) COLLATE utf8mb4_unicode_ci AS name_key,
       CONVERT(CASE WHEN account_type='PERSONAL' THEN CONCAT('P:',HEX(owner_uuid_bin)) ELSE CONCAT('A:',account_id) END USING utf8mb4) COLLATE utf8mb4_unicode_ci AS entity_key
FROM accounts WHERE display_name IS NOT NULL
UNION ALL
SELECT CONVERT('FIRM' USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(CAST(firm_id AS CHAR) USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(TRIM(display_name) USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(CONCAT('F:',firm_id) USING utf8mb4) COLLATE utf8mb4_unicode_ci
FROM firm WHERE NOT (is_archived=1 AND name_released=1)
UNION ALL
SELECT CONVERT('PLAYER' USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(HEX(player_uuid_bin) USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(TRIM(current_name) USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(CONCAT('P:',HEX(player_uuid_bin)) USING utf8mb4) COLLATE utf8mb4_unicode_ci
FROM economy_players WHERE current_name <> HEX(player_uuid_bin)
UNION ALL
SELECT CONVERT('ALIAS' USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(alias USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(TRIM(alias) USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(CONCAT('A:',account_id) USING utf8mb4) COLLATE utf8mb4_unicode_ci
FROM firm_shop_alias_reservations
UNION ALL
SELECT CONVERT('CURRENT_ALIAS' USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(CAST(account_id AS CHAR) USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(TRIM(shop_alias) USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(CONCAT('A:',account_id) USING utf8mb4) COLLATE utf8mb4_unicode_ci
FROM firm_accounts WHERE shop_alias IS NOT NULL;

CREATE PROCEDURE global_names_set(IN p_source VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin,
                                 IN p_subject VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin,
                                 IN p_name VARCHAR(255) CHARACTER SET utf8mb4,
                                 IN p_entity VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin)
BEGIN
    DECLARE old_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;
    DECLARE old_entity VARCHAR(80) DEFAULT NULL;
    DECLARE held_entity VARCHAR(80);
    DECLARE new_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    SET new_name=TRIM(p_name);
    IF new_name IS NULL OR new_name='' THEN
        SIGNAL SQLSTATE '45000' SET MYSQL_ERRNO=1644,
            MESSAGE_TEXT='GLOBAL_NAME_INVALID: choose a nonblank name';
    END IF;
    -- Create/lock the subject row first. A locking read of an absent row takes
    -- a gap lock that can block Treasury's separate default-account transaction
    -- while Business holds its firm transaction open. Placeholder rows are only
    -- transient inside the caller's atomic statement, never a conflict winner.
    INSERT INTO global_name_claims(source_kind,subject_id) VALUES(p_source,p_subject)
    ON DUPLICATE KEY UPDATE subject_id=subject_id;
    SELECT name_key,entity_key INTO old_name,old_entity FROM global_name_claims
      WHERE source_kind=p_source AND subject_id=p_subject FOR UPDATE;
    IF old_name IS NULL OR NOT (old_name=new_name AND BINARY old_entity=BINARY p_entity) THEN
        -- The unique key serializes independent writers. A conflicting claim
        -- rolls back the increment and the caller's entire source statement.
        INSERT INTO global_name_reservations(name_key,entity_key,claim_count)
        VALUES(new_name,p_entity,1)
        ON DUPLICATE KEY UPDATE claim_count=claim_count+1;
        SELECT entity_key INTO held_entity FROM global_name_reservations
          WHERE name_key=new_name FOR UPDATE;
        IF BINARY held_entity<>BINARY p_entity THEN
            SIGNAL SQLSTATE '45000' SET MYSQL_ERRNO=1644,
                MESSAGE_TEXT='GLOBAL_NAME_CONFLICT: name reserved by another identity; choose another name or ask an administrator';
        END IF;
        UPDATE global_name_claims SET name_key=new_name,entity_key=p_entity
          WHERE source_kind=p_source AND subject_id=p_subject;
        IF old_name IS NOT NULL THEN
            -- Delete the reservation only after its last projection has moved.
            -- Delete-before-decrement avoids an intermediate count of zero.
            DELETE FROM global_name_reservations WHERE name_key=old_name AND claim_count=1;
            UPDATE global_name_reservations SET claim_count=claim_count-1 WHERE name_key=old_name;
        END IF;
    END IF;
END;
CREATE PROCEDURE global_names_clear(IN p_source VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin,
                                 IN p_subject VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin)
BEGIN
    DECLARE old_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;
    INSERT INTO global_name_claims(source_kind,subject_id) VALUES(p_source,p_subject)
    ON DUPLICATE KEY UPDATE subject_id=subject_id;
    SELECT name_key INTO old_name FROM global_name_claims
      WHERE source_kind=p_source AND subject_id=p_subject FOR UPDATE;
    DELETE FROM global_name_claims WHERE source_kind=p_source AND subject_id=p_subject;
    IF old_name IS NOT NULL THEN
        DELETE FROM global_name_reservations WHERE name_key=old_name AND claim_count=1;
        UPDATE global_name_reservations SET claim_count=claim_count-1 WHERE name_key=old_name;
    END IF;
END;
CREATE TRIGGER global_names_trim_accounts_insert BEFORE INSERT ON accounts
FOR EACH ROW
BEGIN
    SET NEW.display_name=TRIM(NEW.display_name);
END;
CREATE TRIGGER global_names_trim_accounts_update BEFORE UPDATE ON accounts
FOR EACH ROW
BEGIN
    SET NEW.display_name=TRIM(NEW.display_name);
END;
CREATE TRIGGER global_names_trim_firm_insert BEFORE INSERT ON firm
FOR EACH ROW
BEGIN
    SET NEW.display_name=TRIM(NEW.display_name);
END;
CREATE TRIGGER global_names_trim_firm_update BEFORE UPDATE ON firm
FOR EACH ROW
BEGIN
    SET NEW.display_name=TRIM(NEW.display_name);
END;
CREATE TRIGGER global_names_trim_economy_players_insert BEFORE INSERT ON economy_players
FOR EACH ROW
BEGIN
    SET NEW.current_name=TRIM(NEW.current_name);
END;
CREATE TRIGGER global_names_trim_economy_players_update BEFORE UPDATE ON economy_players
FOR EACH ROW
BEGIN
    SET NEW.current_name=TRIM(NEW.current_name);
END;

-- UUID is known before INSERT, unlike generated account/firm IDs. Guard before
-- ON DUPLICATE KEY UPDATE can choose a row through the unique player-name index.
CREATE TRIGGER global_names_player_identity BEFORE INSERT ON economy_players
FOR EACH ROW
BEGIN
    IF NEW.current_name <> HEX(NEW.player_uuid_bin) THEN
        CALL global_names_set('PLAYER',HEX(NEW.player_uuid_bin),NEW.current_name,CONCAT('P:',HEX(NEW.player_uuid_bin)));
    END IF;
END;
CREATE TRIGGER global_names_accounts_insert AFTER INSERT ON accounts
FOR EACH ROW
BEGIN
    IF NEW.display_name IS NULL THEN
        CALL global_names_clear('ACCOUNT',CAST(NEW.account_id AS CHAR));
    ELSE
        CALL global_names_set('ACCOUNT',CAST(NEW.account_id AS CHAR),NEW.display_name,
          CASE WHEN NEW.account_type='PERSONAL' THEN CONCAT('P:',HEX(NEW.owner_uuid_bin))
               ELSE CONCAT('A:',NEW.account_id) END);
    END IF;
END;
CREATE TRIGGER global_names_accounts_update AFTER UPDATE ON accounts
FOR EACH ROW
BEGIN
    IF NEW.display_name IS NULL THEN
        CALL global_names_clear('ACCOUNT',CAST(NEW.account_id AS CHAR));
    ELSE
        CALL global_names_set('ACCOUNT',CAST(NEW.account_id AS CHAR),NEW.display_name,
          CASE WHEN NEW.account_type='PERSONAL' THEN CONCAT('P:',HEX(NEW.owner_uuid_bin))
               ELSE CONCAT('A:',NEW.account_id) END);
    END IF;
END;
CREATE TRIGGER global_names_firm_insert AFTER INSERT ON firm
FOR EACH ROW
BEGIN
    IF NEW.is_archived=1 AND NEW.name_released=1 THEN
        CALL global_names_clear('FIRM',CAST(NEW.firm_id AS CHAR));
    ELSE
        CALL global_names_set('FIRM',CAST(NEW.firm_id AS CHAR),NEW.display_name,CONCAT('F:',NEW.firm_id));
    END IF;
END;
CREATE TRIGGER global_names_firm_update AFTER UPDATE ON firm
FOR EACH ROW
BEGIN
    IF NEW.is_archived=1 AND NEW.name_released=1 THEN
        CALL global_names_clear('FIRM',CAST(NEW.firm_id AS CHAR));
    ELSE
        CALL global_names_set('FIRM',CAST(NEW.firm_id AS CHAR),NEW.display_name,CONCAT('F:',NEW.firm_id));
    END IF;
END;
CREATE TRIGGER global_names_economy_players_insert AFTER INSERT ON economy_players
FOR EACH ROW
BEGIN
    IF NEW.current_name=HEX(NEW.player_uuid_bin) THEN
        CALL global_names_clear('PLAYER',HEX(NEW.player_uuid_bin));
    ELSE
        CALL global_names_set('PLAYER',HEX(NEW.player_uuid_bin),NEW.current_name,CONCAT('P:',HEX(NEW.player_uuid_bin)));
    END IF;
END;
CREATE TRIGGER global_names_economy_players_update AFTER UPDATE ON economy_players
FOR EACH ROW
BEGIN
    IF NEW.current_name=HEX(NEW.player_uuid_bin) THEN
        CALL global_names_clear('PLAYER',HEX(NEW.player_uuid_bin));
    ELSE
        CALL global_names_set('PLAYER',HEX(NEW.player_uuid_bin),NEW.current_name,CONCAT('P:',HEX(NEW.player_uuid_bin)));
    END IF;
END;
CREATE TRIGGER global_names_firm_shop_alias_reservations_insert AFTER INSERT ON firm_shop_alias_reservations
FOR EACH ROW
BEGIN
    CALL global_names_set('ALIAS',NEW.alias,NEW.alias,CONCAT('A:',NEW.account_id));
END;
CREATE TRIGGER global_names_firm_shop_alias_reservations_update AFTER UPDATE ON firm_shop_alias_reservations
FOR EACH ROW
BEGIN
    CALL global_names_set('ALIAS',NEW.alias,NEW.alias,CONCAT('A:',NEW.account_id));
END;
CREATE TRIGGER global_names_firm_accounts_insert AFTER INSERT ON firm_accounts
FOR EACH ROW
BEGIN
    IF NEW.shop_alias IS NULL THEN
        CALL global_names_clear('CURRENT_ALIAS',CAST(NEW.account_id AS CHAR));
    ELSE
        CALL global_names_set('CURRENT_ALIAS',CAST(NEW.account_id AS CHAR),NEW.shop_alias,CONCAT('A:',NEW.account_id));
        -- Even SQL/admin writers retain old sign ownership before changing it.
        INSERT INTO firm_shop_alias_reservations(alias,account_id) VALUES(NEW.shop_alias,NEW.account_id)
        ON DUPLICATE KEY UPDATE alias=alias;
    END IF;
END;
CREATE TRIGGER global_names_firm_accounts_update AFTER UPDATE ON firm_accounts
FOR EACH ROW
BEGIN
    IF NEW.shop_alias IS NULL THEN
        CALL global_names_clear('CURRENT_ALIAS',CAST(NEW.account_id AS CHAR));
    ELSE
        CALL global_names_set('CURRENT_ALIAS',CAST(NEW.account_id AS CHAR),NEW.shop_alias,CONCAT('A:',NEW.account_id));
        -- Even SQL/admin writers retain old sign ownership before changing it.
        INSERT INTO firm_shop_alias_reservations(alias,account_id) VALUES(NEW.shop_alias,NEW.account_id)
        ON DUPLICATE KEY UPDATE alias=alias;
    END IF;
END;
