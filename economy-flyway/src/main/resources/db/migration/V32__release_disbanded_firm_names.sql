-- PAR-154: keep historical names, but reserve them only while a firm is active.
-- NULL values do not collide in a unique index, allowing repeated disband/reuse.
ALTER TABLE firm
    DROP INDEX uq_firm_display_name,
    ADD COLUMN active_display_name VARCHAR(255) COLLATE utf8mb4_unicode_ci
        GENERATED ALWAYS AS (CASE WHEN is_archived = 0 THEN display_name ELSE NULL END) STORED,
    ADD UNIQUE KEY uq_firm_active_display_name (active_display_name),
    ADD KEY idx_firm_display_name (display_name);
