-- Rename the explorer capability 'staff.audit' -> 'viewer' (PAR-265).
--
-- The read-only financial-oversight capability shipped under the internal name
-- 'staff.audit' (V10 / PAR-98). It is now surfaced as the user-facing 'viewer'
-- role: grantable on a group, documented, and distributed to linked players by
-- the LuckPerms reconciliation cron. capability is a free-form VARCHAR in the
-- explorer's vocabulary (no enum/constraint), so this is a pure data rename of
-- any existing group-capability rows.
--
-- The explorer also resolves 'staff.audit' as a legacy alias at runtime
-- (normalizeCapability in lib/auth/capabilities.ts), so access never depends on
-- this migration having run — but renaming the rows keeps the stored vocabulary
-- and the admin UI in agreement, and lets the alias be retired later.
--
-- Idempotent: re-running is a no-op once the rows already read 'viewer'. The
-- (group_id, capability) primary key means a group can't end up with both the
-- old and new value at once; INSERT ... ON DUPLICATE is unnecessary because a
-- group holding 'viewer' already would never also hold 'staff.audit'.
UPDATE explorer_group_capability
   SET capability = 'viewer'
 WHERE capability = 'staff.audit';
