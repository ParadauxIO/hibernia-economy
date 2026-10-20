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
