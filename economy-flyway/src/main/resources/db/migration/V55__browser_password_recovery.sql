-- A browser secret authorizes completion; the short code only authorizes in-game approval.
-- Never store passwords, browser secrets, or short codes in plaintext.
CREATE TABLE explorer_password_recovery (
    browser_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    code_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    keycloak_sub VARCHAR(255) NULL,
    player_uuid_bin BINARY(16) NULL,
    expires_at TIMESTAMP NOT NULL,
    UNIQUE KEY uq_password_recovery_subject (keycloak_sub),
    KEY idx_password_recovery_expiry (expires_at)
);

INSERT INTO feature_flags (name, description)
VALUES ('explorer.browser_recovery', 'Recover a linked web account through browser-bound in-game confirmation')
ON DUPLICATE KEY UPDATE name = VALUES(name);
