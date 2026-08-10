-- Unified player directory: UUID ↔ current name plus last-login epoch, owned and
-- written by Treasury (PAR-35). This is the consolidation target for the two
-- overlapping stores — Business's `firm_players` (uuid↔name) and Treasury's
-- `player_login_times` (last-login epoch). Introduced ADDITIVELY: both legacy
-- tables and all their existing readers (Business, treasury-rest-api, explorer)
-- keep working untouched, and a later migration drops them once every consumer
-- has moved over. Backfilled from both so the directory is complete on day one.
CREATE TABLE economy_players (
    player_uuid_bin  BINARY(16)  NOT NULL,
    current_name     VARCHAR(32) NOT NULL,
    name_lower       VARCHAR(32) AS (LOWER(current_name)) VIRTUAL,
    first_seen       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
                         ON UPDATE CURRENT_TIMESTAMP,
    last_login_epoch BIGINT      NULL,
    CONSTRAINT pk_economy_players PRIMARY KEY (player_uuid_bin),
    CONSTRAINT uq_economy_players_name UNIQUE (name_lower)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Names + first/last-seen come from the existing firm_players directory.
INSERT INTO economy_players (player_uuid_bin, current_name, first_seen, last_seen)
    SELECT player_uuid_bin, current_name, first_seen, last_seen
    FROM firm_players;

-- Fold in each player's last-login epoch where Treasury has recorded one.
UPDATE economy_players ep
  JOIN player_login_times plt ON plt.player_uuid_bin = ep.player_uuid_bin
   SET ep.last_login_epoch = plt.last_login_epoch;

-- Seed rows for players Treasury has a login epoch for but who were never in the
-- firm_players directory (they logged in but never touched a firm). Without this,
-- statement 1 skips them (they aren't in firm_players) and statement 2 can't add an
-- epoch to a row that doesn't exist, so V19's DROP of player_login_times would lose
-- their last-login epoch permanently — breaking the next-login balance-tax proration
-- that reads economy_players.last_login_epoch. We have no name for them (firm_players
-- was the only name source and it doesn't carry them), so we seed a synthetic
-- placeholder: HEX(uuid) is exactly 32 chars (fits current_name VARCHAR(32)), unique,
-- and can never collide with a real Minecraft IGN (≤16 chars). The placeholder is
-- self-healing — Treasury's PlayerLoginListener upserts the real current_name on the
-- player's next login (EconomyPlayerMapper: ON DUPLICATE KEY UPDATE current_name).
-- Until then these players display as their hex placeholder in explorer search and
-- are unsearchable by real name; that is cosmetic and self-corrects on relogin. The
-- epoch — the datum that matters — is preserved from day one.
INSERT INTO economy_players (player_uuid_bin, current_name, last_login_epoch)
    SELECT plt.player_uuid_bin, HEX(plt.player_uuid_bin), plt.last_login_epoch
    FROM player_login_times plt
    LEFT JOIN economy_players ep ON ep.player_uuid_bin = plt.player_uuid_bin
    WHERE ep.player_uuid_bin IS NULL;
