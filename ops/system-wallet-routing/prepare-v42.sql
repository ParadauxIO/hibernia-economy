-- Optional pre-migration SYSTEM routing preparation. Run from the repository root with mariadb,
-- ALL writers stopped, no --force. See wiki/system-wallet-routing.md.
-- This wrapper deliberately makes no Flyway history changes or label renames.
ALTER TABLE accounts
    ADD COLUMN IF NOT EXISTS system_plugin_key VARCHAR(255)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL;
DROP TEMPORARY TABLE IF EXISTS system_wallet_before_v42;
CREATE TEMPORARY TABLE system_wallet_before_v42 AS
SELECT a.account_id,a.account_type,a.owner_uuid_bin,a.display_name,
       COALESCE(a.system_plugin_key,a.display_name) AS expected_key,
       b.balance,b.version AS balance_version
  FROM accounts a LEFT JOIN account_balances_mat b USING(account_id)
 WHERE a.account_type='SYSTEM';

SOURCE economy-flyway/src/main/resources/db/migration/V42__stable_system_plugin_key.sql;

DELIMITER $$
DROP PROCEDURE IF EXISTS assert_system_wallet_preparation$$
CREATE PROCEDURE assert_system_wallet_preparation()
BEGIN
    IF (SELECT COUNT(*) FROM accounts WHERE account_type='SYSTEM')
          <> (SELECT COUNT(*) FROM system_wallet_before_v42)
       OR EXISTS (
         SELECT 1 FROM system_wallet_before_v42 saved
         LEFT JOIN accounts a USING(account_id)
         LEFT JOIN account_balances_mat b USING(account_id)
         WHERE a.account_id IS NULL
            OR a.account_type<>saved.account_type
            OR NOT (a.owner_uuid_bin <=> saved.owner_uuid_bin)
            OR NOT (BINARY a.display_name <=> BINARY saved.display_name)
            OR NOT (BINARY a.system_plugin_key <=> BINARY saved.expected_key)
            OR NOT (b.balance <=> saved.balance)
            OR NOT (b.version <=> saved.balance_version)
       ) THEN
        SIGNAL SQLSTATE '45000' SET MYSQL_ERRNO=1644,
          MESSAGE_TEXT='SYSTEM preparation changed wallet identity, label, owner or balance; keep writers stopped';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
       WHERE table_schema=DATABASE() AND table_name='accounts'
         AND index_name='uq_one_system_per_plugin' AND non_unique=0 AND column_name='_system_plugin') THEN
        SIGNAL SQLSTATE '45000' SET MYSQL_ERRNO=1644, MESSAGE_TEXT='SYSTEM unique routing index missing';
    END IF;
END$$
CALL assert_system_wallet_preparation()$$
DROP PROCEDURE assert_system_wallet_preparation$$
DELIMITER ;
DROP TEMPORARY TABLE system_wallet_before_v42;
SELECT 'SYSTEM routing preparation verified; keep maintenance paused for compatible bundle and normal migration' AS result;
