package io.paradaux.business.model;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Value;

/** Immutable snapshot returned by the Business API. */
@Value
public class FirmEmployee {
    private Integer firmId;
    private UUID playerUuid;
    private String roleName;
    private LocalDateTime joinedAt;
    private LocalDateTime leftAt;
    private UUID addedBy;
    private UUID removedBy;
    private Boolean current;
}
