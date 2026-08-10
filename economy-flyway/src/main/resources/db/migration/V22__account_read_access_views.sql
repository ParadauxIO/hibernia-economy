-- ADT-13: a single source of truth for account READ access over account_access.
--
-- The "who may read this account's history/balance" decision was reimplemented
-- three times and had already drifted: a VIEWER-tier grant could read on the web
-- explorer but was 403'd by the REST API, and the explorer even disagreed with
-- itself (canReadAccount allowed VIEWER, the /me account list did not).
--
-- These two named views encode the canonical rule per surface so every consumer
-- reads the same definition instead of hand-restating the level filter:
--
--   account_read_access_api  — MEMBER/AUTHORIZER only. Consumed by the public
--       REST API (treasury-rest-api) and the in-game plugin (treasury). A
--       read-only VIEWER is NOT a member for programmatic access.
--   account_read_access_web  — VIEWER too. Consumed by the economy-explorer web
--       UI, where a government department secretary (a VIEWER) is intentionally
--       allowed to read their department's ledger history (PAR-237). The web
--       surface is deliberately more permissive than the public API.
--
-- account_access is the ordered scale VIEWER < MEMBER < AUTHORIZER (PAR-249),
-- soft-deleted via removed_at; both views expose only the active grant set. The
-- OWNER path is checked separately by each caller (the owner is not necessarily
-- an account_access row), so it is intentionally not folded in here.
--
-- Additive and idempotent (CREATE OR REPLACE VIEW); no data change.

CREATE OR REPLACE VIEW account_read_access_api AS
SELECT account_id, subject_uuid_bin
  FROM account_access
 WHERE level IN ('MEMBER', 'AUTHORIZER')
   AND removed_at IS NULL;

CREATE OR REPLACE VIEW account_read_access_web AS
SELECT account_id, subject_uuid_bin
  FROM account_access
 WHERE level IN ('VIEWER', 'MEMBER', 'AUTHORIZER')
   AND removed_at IS NULL;
