-- Published snapshot of selected plugin configuration (PAR-80).
--
-- The config files stay the source of truth: the owning plugin upserts the
-- values it actually applies on every start and reload, so the explorer docs and
-- the REST API can show live tunables (tax rates first) instead of hand-copied
-- numbers that drift. Nothing reads this table to decide behaviour.
--
-- One row per logical setting. `owner` names the publishing plugin, which
-- removes its own keys that it no longer publishes; `updated_at` moves only when
-- the stored value changes (ON UPDATE CURRENT_TIMESTAMP is skipped when every
-- other column is written with its current value), so it reads as "in effect
-- since" rather than "last restart".
CREATE TABLE economy_config (
    config_key   VARCHAR(128) NOT NULL PRIMARY KEY,
    owner        VARCHAR(32)  NOT NULL,
    value_type   ENUM('BOOLEAN','DECIMAL','JSON') NOT NULL,
    config_value TEXT         NOT NULL,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_economy_config_owner (owner)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
