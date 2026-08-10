-- Make the /firm admin set proprietor staff/DOC override auditable (PAR-315).
--
-- adminSetProprietor changed firm.proprietor_uuid_bin with NO record at all — no
-- firm_transfer_requests row, and updateFirm even froze updated_at — so a forced
-- reassignment was indistinguishable from data corruption (the firms found with a
-- changed proprietor but no transfer row were exactly this). The override now logs
-- into the same handover table as consent transfers:
--
--   * ADMIN_OVERRIDE — a new TERMINAL status. active_only is
--     (status IN ('PENDING','CONFIRMED')) → NULL for this value, so it never
--     collides with the uq_one_active_transfer one-active-per-firm constraint.
--   * actor_uuid_bin — the staff member who performed the override. NULL for
--     ordinary player transfers, where from_uuid_bin already identifies the actor.
--
-- Additive: existing rows keep actor_uuid_bin = NULL and the enum only gains a
-- value, so every existing reader/writer is unaffected.
ALTER TABLE firm_transfer_requests
    MODIFY COLUMN status ENUM('PENDING','CONFIRMED','ACCEPTED','CANCELLED','EXPIRED','ADMIN_OVERRIDE')
        NOT NULL DEFAULT 'PENDING',
    ADD COLUMN actor_uuid_bin BINARY(16) NULL AFTER to_uuid_bin;
