package io.paradaux.business.model;

import lombok.Value;

/** Immutable snapshot returned by the Business API. */
@Value
public class FirmRolePermission {
    private Integer firmId;
    private String roleName;
    private RolePermission permission;
}
