-- Audit log detail for the explorer's /admin/audit page (PAR-542).
--
-- 1. action: a stable verb for each row ('view', 'account.rename',
--    'relationship.read', ...), so the page can filter by what happened instead
--    of by request path. Existing rows are backfilled from the paths the
--    explorer has written; reads (GET, and the Treasury plugin's in-game CMD
--    rows) are 'view', and any other path is 'other'. Writers that don't know
--    the column (the Treasury plugin's ExplorerAuditMapper) get the default.
-- 2. before_json / after_json: what an admin mutation changed. NULL for reads.
--    JSON text, checked like MariaDB's JSON alias; the explorer caps each value
--    at 16 KiB before writing.
-- 3. idx_audit_action (action, at): the action chip seeks it, newest first.
--    The actor (idx_audit_actor), target (idx_audit_target) and time
--    (idx_audit_at) chips already have indexes from V3 and V9.
-- 4. The audit.read permission (opens /admin/audit) is granted to the admin
--    role, if that role still exists. Like every permission it can then be
--    edited in Admin → Roles.
ALTER TABLE explorer_audit
    ADD COLUMN action      VARCHAR(48) NOT NULL DEFAULT 'view' AFTER actor_role,
    ADD COLUMN before_json LONGTEXT    NULL CHECK (before_json IS NULL OR JSON_VALID(before_json)),
    ADD COLUMN after_json  LONGTEXT    NULL CHECK (after_json IS NULL OR JSON_VALID(after_json)),
    ADD INDEX idx_audit_action (action, at);

UPDATE explorer_audit
   SET action = CASE path
        WHEN '/admin/accounts/transfer'      THEN 'account.transfer'
        WHEN '/admin/accounts/rename'        THEN 'account.rename'
        WHEN '/admin/accounts/owner'         THEN 'account.owner.change'
        WHEN '/admin/accounts/archive'       THEN 'account.archive'
        WHEN '/admin/accounts/unarchive'     THEN 'account.unarchive'
        WHEN '/admin/firms/disband'          THEN 'firm.disband'
        WHEN '/admin/firms/rename'           THEN 'firm.rename'
        WHEN '/admin/firms/details'          THEN 'firm.details.update'
        WHEN '/admin/api-keys/revoke'        THEN 'api_key.revoke'
        WHEN '/admin/api-keys/rotate'        THEN 'api_key.rotate'
        WHEN '/admin/api-keys/rate-limit'    THEN 'rate_limit.update'
        WHEN '/admin/webhooks/create'        THEN 'webhook.create'
        WHEN '/admin/webhooks/active'        THEN 'webhook.active'
        WHEN '/admin/webhooks/delete'        THEN 'webhook.delete'
        WHEN '/me/webhooks/create'           THEN 'webhook.create'
        WHEN '/me/webhooks/active'           THEN 'webhook.active'
        WHEN '/me/webhooks/url'              THEN 'webhook.url'
        WHEN '/me/webhooks/rotate'           THEN 'webhook.secret.rotate'
        WHEN '/me/webhooks/delete'           THEN 'webhook.delete'
        WHEN '/admin/groups/create'          THEN 'group.create'
        WHEN '/admin/groups/delete'          THEN 'group.delete'
        WHEN '/admin/groups/capabilities'    THEN 'group.roles.update'
        WHEN '/admin/groups/luckperms-node'  THEN 'group.sync.update'
        WHEN '/admin/groups/member-add'      THEN 'group.member.add'
        WHEN '/admin/groups/member-remove'   THEN 'group.member.remove'
        ELSE 'other'
       END
 WHERE method NOT IN ('GET', 'CMD');

INSERT IGNORE INTO explorer_role_permission (role_key, permission)
SELECT role_key, 'audit.read' FROM explorer_role_definition WHERE role_key = 'admin';
