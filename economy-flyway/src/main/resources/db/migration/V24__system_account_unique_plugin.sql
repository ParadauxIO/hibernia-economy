-- ADT-74: give SYSTEM accounts a uniqueness backstop, like PERSONAL accounts have.
--
-- AccountServiceImpl.getOrCreateSystemAccountId(plugin) does check-then-insert
-- (findSystemAccountForPlugin → insertAccount) with NO database constraint
-- behind it, unlike getOrCreatePersonalAccountId which leans on
-- uq_one_personal_per_player. Two concurrent first-resolves for the same plugin
-- could therefore each insert a SYSTEM row (a faucet/sink account) with the same
-- display_name, silently splitting that plugin's balance across two accounts.
--
-- Mirror the V1 _personal_owner trick: a virtual column that is non-NULL only for
-- SYSTEM rows (display_name is the plugin identity that findSystemAccountForPlugin
-- matches on) + a UNIQUE over it. MariaDB treats NULLs as distinct, so non-SYSTEM
-- rows (NULL here) are unaffected, exactly as _personal_owner leaves non-PERSONAL
-- rows alone. The loser of the race then trips a duplicate-key error and
-- re-resolves to the winner's id (see getOrCreateSystemAccountId).
--
-- Verified safe against the live tenant (2026-06-28): 16 SYSTEM accounts, all 16
-- display_names distinct and non-NULL (max length 17), so the UNIQUE adds without
-- violating any existing row.
ALTER TABLE accounts
    ADD COLUMN _system_plugin VARCHAR(255) AS (
        CASE WHEN account_type = 'SYSTEM' THEN display_name END
    ) VIRTUAL,
    ADD UNIQUE KEY uq_one_system_per_plugin (_system_plugin);
