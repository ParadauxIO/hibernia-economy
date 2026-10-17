package io.paradaux.business.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor // consistent with the sibling DTOs; needed for MyBatis/JSON reflective instantiation (ADT inconsistent-noargs-ctor)
public class FirmRolePermission {
    private Integer firmId;
    private String roleName;
    private RolePermission permission;
}
