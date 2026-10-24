/** Server Auth package: use policy.ts for dependency-free RBAC in edge code. */
export { getViewer, type Viewer } from './viewer';
export * from './access';
export { PERMISSIONS, PERMISSION_DESCRIPTIONS, hasAnyPermission, type Permission } from './policy';
export { CAPABILITIES, type Role } from './capabilities';
export { requireAdmin } from './requireAdmin';

export { assertCanGrantRoles, withPolicyMutation } from './delegation';
