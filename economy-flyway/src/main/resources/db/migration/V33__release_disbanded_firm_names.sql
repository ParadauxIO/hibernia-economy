-- PAR-154: names stay reserved after disband until an administrator releases them.
-- Existing archived firms remain reserved. NULL permits repeated explicit releases
-- without rewriting display names or losing historical firm IDs.
ALTER TABLE firm
    DROP INDEX uq_firm_display_name,
    ADD COLUMN name_released TINYINT(1) NOT NULL DEFAULT 0,
    ADD COLUMN reserved_display_name VARCHAR(255) COLLATE utf8mb4_unicode_ci
        GENERATED ALWAYS AS (CASE WHEN is_archived = 1 AND name_released = 1
                                 THEN NULL ELSE display_name END) STORED,
    ADD UNIQUE KEY uq_firm_reserved_display_name (reserved_display_name),
    ADD KEY idx_firm_display_name (display_name);
