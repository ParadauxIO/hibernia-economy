-- Former employment must not occupy a unique slot: a player can leave and
-- rejoin the same firm more than once. NULL permits multiple historical rows
-- while 1 still enforces exactly one current employment per firm/player.
ALTER TABLE firm_employee
    MODIFY COLUMN is_current TINYINT(1)
        GENERATED ALWAYS AS (CASE WHEN left_at IS NULL THEN 1 ELSE NULL END) VIRTUAL;
