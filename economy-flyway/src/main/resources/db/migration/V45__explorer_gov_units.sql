-- Government workspace (PAR-539, spec §10): government units, the compliance
-- watchlist, and the permission keys and role that gate them.
--
-- Units are a PRESENTATION grouping only. They never grant access: an official
-- sees a unit's figures because they can read its accounts (RBAC
-- government.read + the account_read_access_web relationship), and a unit's
-- totals only ever include the accounts the viewer can read. The explorer owns
-- these tables and is the only writer; nothing in the economy reads them.

-- A department, ministry, city or state. parent_unit_id gives the hierarchy
-- (nation → department, or state → city). Deleting a parent leaves its children
-- at the top level rather than deleting them.
CREATE TABLE explorer_gov_unit (
    unit_id             INT UNSIGNED  NOT NULL AUTO_INCREMENT,
    slug                VARCHAR(64)   NOT NULL,
    name                VARCHAR(128)  NOT NULL,
    parent_unit_id      INT UNSIGNED  NULL,
    description         VARCHAR(500)  NULL,
    created_at          TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by_uuid_bin BINARY(16)    NULL,
    PRIMARY KEY (unit_id),
    UNIQUE KEY uq_gov_unit_slug (slug),
    KEY idx_gov_unit_parent (parent_unit_id),
    CONSTRAINT fk_gov_unit_parent FOREIGN KEY (parent_unit_id)
        REFERENCES explorer_gov_unit(unit_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Which GOVERNMENT accounts a unit holds. An account belongs to at most one
-- unit, so unit totals never count an account twice; a parent's totals include
-- its descendants' accounts. The account type is checked by the admin action,
-- not here (account types are not expected to change).
CREATE TABLE explorer_gov_unit_account (
    unit_id    INT UNSIGNED NOT NULL,
    account_id INT UNSIGNED NOT NULL,
    PRIMARY KEY (unit_id, account_id),
    UNIQUE KEY uq_gov_unit_account (account_id),
    CONSTRAINT fk_gov_unit_account_unit FOREIGN KEY (unit_id)
        REFERENCES explorer_gov_unit(unit_id) ON DELETE CASCADE,
    CONSTRAINT fk_gov_unit_account_account FOREIGN KEY (account_id)
        REFERENCES accounts(account_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Accounts a compliance officer watches (/gov/compliance). Personal to the
-- viewer; adding one never grants read access to it.
CREATE TABLE explorer_watchlist (
    viewer_uuid_bin BINARY(16)   NOT NULL,
    account_id      INT UNSIGNED NOT NULL,
    created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (viewer_uuid_bin, account_id),
    KEY idx_watchlist_account (account_id),
    CONSTRAINT fk_watchlist_account FOREIGN KEY (account_id)
        REFERENCES accounts(account_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- New permission keys (lib/auth/policy.ts), seeded into the default roles.
-- Roles are editable records (V31): an environment may have renamed away or
-- deleted a default role, so seed only the roles that still exist.
INSERT IGNORE INTO explorer_role_permission (role_key, permission)
SELECT d.role_key, p.permission
  FROM explorer_role_definition d
  JOIN (SELECT 'government' AS role_key, 'government.audit.read' AS permission
        UNION ALL SELECT 'admin', 'government.audit.read'
        UNION ALL SELECT 'admin', 'gov_units.manage') p ON p.role_key = d.role_key;

-- The compliance tools (spec §10.4) are gated by firm.financials.read.all
-- together with transactions.read. This role bundles them so it is ready to
-- use, but it is attached to nobody: an admin links it to an explorer group
-- (for example one synced from a LuckPerms department group) in /admin/groups,
-- and membership then follows LuckPerms. No group, department or server is
-- named here.
INSERT IGNORE INTO explorer_role_definition (role_key, name, description) VALUES
  ('compliance', 'Compliance', 'Firm investigations and the watchlist: every firm''s financials, the global ledger and sales attribution. Attach to a group to use.');
INSERT IGNORE INTO explorer_role_permission (role_key, permission) VALUES
  ('compliance', 'firm.financials.read'),
  ('compliance', 'firm.financials.read.all'),
  ('compliance', 'transactions.read'),
  ('compliance', 'market.sales.read');
