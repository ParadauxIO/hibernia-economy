-- PAR-556: release controls, independent of permissions and the retired V1 privacy gate.
CREATE TABLE feature_flags (
    name VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    description VARCHAR(512) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 0,
    revision INT NOT NULL DEFAULT 1,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    updated_by VARCHAR(255) NULL,
    CONSTRAINT feature_flags_enabled CHECK (enabled IN (0, 1)),
    CONSTRAINT feature_flags_revision CHECK (revision > 0),
    CONSTRAINT feature_flags_name CHECK (name NOT REGEXP '[^a-z0-9_.]' AND name REGEXP '^[a-z][a-z0-9_]*[.][a-z][a-z0-9_.]*$')
) ENGINE=InnoDB;

INSERT IGNORE INTO explorer_role_permission (role_key, permission) VALUES
    ('admin', 'feature_flags.read'),
    ('admin', 'feature_flags.update');
