-- Roles are configurable records; permission names remain an application vocabulary.
CREATE TABLE explorer_role_definition (
    role_key VARCHAR(64) NOT NULL PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    description VARCHAR(255) NULL,
    revision INT UNSIGNED NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE explorer_role_permission (
    role_key VARCHAR(64) NOT NULL,
    permission VARCHAR(64) NOT NULL,
    PRIMARY KEY (role_key, permission),
    CONSTRAINT fk_explorer_permission_role FOREIGN KEY (role_key)
      REFERENCES explorer_role_definition(role_key) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO explorer_role_definition (role_key, name, description) VALUES
('player', 'Player', 'Authenticated baseline; resource reads require a relationship.'),
('government', 'Government', 'Government records scoped to account relationships.'),
('moderator', 'Moderator', 'Read-only investigations across the economy.'),
('admin', 'Admin', 'All initial permissions; editable like any other role.');
-- Preserve any pre-existing custom grant names, but never invent permissions for them.
INSERT IGNORE INTO explorer_role_definition (role_key, name)
SELECT role, role FROM explorer_role;
INSERT IGNORE INTO explorer_role_definition (role_key, name)
SELECT capability, capability FROM explorer_group_capability;
INSERT INTO explorer_role_permission (role_key, permission) VALUES
('player', 'player.read'),
('player', 'account.read'),
('player', 'firm.read'),
('player', 'firm.financials.read'),
('player', 'webhooks.read'),
('player', 'webhooks.manage'),
('government', 'government.read'),
('moderator', 'player.read.all'),
('moderator', 'account.read.all'),
('moderator', 'firm.read.all'),
('moderator', 'firm.financials.read.all'),
('moderator', 'government.read.all'),
('moderator', 'transactions.read'),
('moderator', 'market.sales.read'),
('moderator', 'market.figures.read'),
('admin', 'player.read'),
('admin', 'player.read.all'),
('admin', 'account.read'),
('admin', 'account.read.all'),
('admin', 'firm.read'),
('admin', 'firm.read.all'),
('admin', 'firm.financials.read'),
('admin', 'firm.financials.read.all'),
('admin', 'government.read'),
('admin', 'government.read.all'),
('admin', 'transactions.read'),
('admin', 'market.sales.read'),
('admin', 'market.figures.read'),
('admin', 'account.admin.read'),
('admin', 'account.rename'),
('admin', 'account.owner.change'),
('admin', 'account.archive'),
('admin', 'account.transfer'),
('admin', 'firm.admin.read'),
('admin', 'firm.rename'),
('admin', 'firm.details.update'),
('admin', 'firm.disband'),
('admin', 'api_keys.read'),
('admin', 'api_keys.revoke'),
('admin', 'api_keys.rotate'),
('admin', 'rate_limits.update'),
('admin', 'webhooks.read'),
('admin', 'webhooks.manage'),
('admin', 'webhooks.read.all'),
('admin', 'webhooks.create.all'),
('admin', 'webhooks.update.all'),
('admin', 'webhooks.delete.all'),
('admin', 'groups.read'),
('admin', 'groups.create'),
('admin', 'groups.delete'),
('admin', 'groups.members.update'),
('admin', 'groups.roles.update'),
('admin', 'groups.sync.update'),
('admin', 'roles.read'),
('admin', 'roles.create'),
('admin', 'roles.update'),
('admin', 'roles.delete'),
('admin', 'roles.assign'),
('admin', 'docs.admin.read');
ALTER TABLE explorer_role ADD CONSTRAINT fk_explorer_direct_role
  FOREIGN KEY (role) REFERENCES explorer_role_definition(role_key) ON DELETE CASCADE;
ALTER TABLE explorer_group_capability ADD CONSTRAINT fk_explorer_group_role
  FOREIGN KEY (capability) REFERENCES explorer_role_definition(role_key) ON DELETE CASCADE;
