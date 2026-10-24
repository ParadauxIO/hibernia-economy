-- ADT-6: stop persisting the bearer credential at rest.
--
-- api_keys.token held the full signed JWT verbatim. Both the treasury-api-plugin
-- (mint) and treasury-rest-api (rotate) wrote it, and the explorer + REST API
-- connect to this DB with broad grants — so any read, backup, or dump yielded
-- live, replayable API keys for every account/firm for up to 180 days.
-- Verification only ever needs jwt_id (jti) by kid; the token is shown once at
-- issue/reissue and is never read back.
--
-- Make the column nullable and PURGE every stored token. We deliberately do not
-- DROP it in the same migration: the running Paper plugin is redeployed by a
-- server restart (not an instant rollout), and an old instance still INSERTs the
-- column until then — a nullable column keeps that working while the new code
-- (which omits token entirely) ships. A follow-up migration drops the now-always
-- -NULL column once every writer is on the new build.

ALTER TABLE api_keys
    MODIFY COLUMN token TEXT NULL
    COMMENT 'deprecated — no longer persisted (ADT-6); always NULL, slated for removal';

UPDATE api_keys SET token = NULL WHERE token IS NOT NULL;
