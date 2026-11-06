package io.paradaux.business.model;

import lombok.Value;

/** Immutable snapshot returned by the Business API. */
@Value
public class FirmRole {
    private Integer firmId;
    private String roleName;
    private Integer roleRankOrder;
}
