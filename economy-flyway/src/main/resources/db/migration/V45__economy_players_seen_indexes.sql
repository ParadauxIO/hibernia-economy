-- The explorer's public player directory (/players, PAR-537) filters and sorts
-- by first and last seen. economy_players is about 140k rows, so without these
-- every directory page sorted by "last seen" (the default) would scan and sort
-- the whole table. Both are plain secondary indexes: Treasury updates last_seen
-- once per login, so the write cost is one extra index entry per login.
-- Renumber at merge time if another branch takes V45 first.
ALTER TABLE economy_players
    ADD INDEX idx_economy_players_first_seen (first_seen),
    ADD INDEX idx_economy_players_last_seen (last_seen);
