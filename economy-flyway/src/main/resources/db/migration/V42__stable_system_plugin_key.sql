-- Stable plugin routing identity, separate from the globally reserved display label.
-- Also the versioned PRE-V40 preparation script: see wiki/system-wallet-routing.md.
-- ALL WRITERS MUST BE STOPPED. DDL commits implicitly; every completed stage can
-- be replayed. Never edit V24, renumber history, or rederive a non-NULL key.
DELIMITER $$
ALTER TABLE accounts
    ADD COLUMN IF NOT EXISTS system_plugin_key VARCHAR(255)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL$$

-- Fail before backfill if the original identity cannot be captured unambiguously.
DROP PROCEDURE IF EXISTS system_plugin_key_preflight$$
CREATE PROCEDURE system_plugin_key_preflight()
BEGIN
    IF EXISTS (SELECT 1 FROM accounts WHERE account_type='SYSTEM'
        AND (COALESCE(system_plugin_key,display_name) IS NULL
             OR TRIM(COALESCE(system_plugin_key,display_name))=''))
       OR EXISTS (SELECT 1 FROM accounts WHERE account_type<>'SYSTEM' AND system_plugin_key IS NOT NULL)
       OR EXISTS (SELECT 1 FROM accounts WHERE account_type='SYSTEM'
          GROUP BY COALESCE(system_plugin_key,display_name) HAVING COUNT(*)>1) THEN
        SIGNAL SQLSTATE '45000' SET MYSQL_ERRNO=1644,
          MESSAGE_TEXT='SYSTEM routing keys must be nonblank and unique; non-SYSTEM keys must be NULL';
    END IF;
END$$
CALL system_plugin_key_preflight()$$
DROP PROCEDURE system_plugin_key_preflight$$

-- Preserve captured identity even if the human label has since been renamed.
UPDATE accounts SET system_plugin_key=display_name
 WHERE account_type='SYSTEM' AND system_plugin_key IS NULL$$

-- Replace V24's expression and its index in ONE ALTER. No committed stage drops
-- the unique backstop. COPY supports indexed virtual-column expression changes.
ALTER TABLE accounts
    DROP INDEX uq_one_system_per_plugin,
    MODIFY COLUMN _system_plugin VARCHAR(255) AS (
        CASE WHEN account_type='SYSTEM' THEN system_plugin_key END
    ) VIRTUAL,
    ADD UNIQUE KEY uq_one_system_per_plugin (_system_plugin),
    ALGORITHM=COPY$$

-- Legacy INSERTs remain compatible before any label rename. New mapper inserts
-- explicitly supply the same key; direct writers get it captured here as well.
CREATE OR REPLACE TRIGGER system_plugin_key_insert
BEFORE INSERT ON accounts FOR EACH ROW
BEGIN
    IF NEW.account_type='SYSTEM' AND NEW.system_plugin_key IS NULL THEN
        SET NEW.system_plugin_key=NEW.display_name;
    END IF;
    IF (NEW.account_type='SYSTEM' AND (NEW.system_plugin_key IS NULL OR TRIM(NEW.system_plugin_key)=''))
       OR (NEW.account_type<>'SYSTEM' AND NEW.system_plugin_key IS NOT NULL) THEN
        SIGNAL SQLSTATE '45000' SET MYSQL_ERRNO=1644, MESSAGE_TEXT='Invalid SYSTEM routing key';
    END IF;
END$$

CREATE OR REPLACE TRIGGER system_plugin_key_update
BEFORE UPDATE ON accounts FOR EACH ROW
BEGIN
    IF NOT (BINARY NEW.system_plugin_key <=> BINARY OLD.system_plugin_key)
       OR ((OLD.account_type='SYSTEM' OR NEW.account_type='SYSTEM') AND NEW.account_type<>OLD.account_type) THEN
        SIGNAL SQLSTATE '45000' SET MYSQL_ERRNO=1644, MESSAGE_TEXT='SYSTEM routing identity is immutable';
    END IF;
END$$
DELIMITER ;
