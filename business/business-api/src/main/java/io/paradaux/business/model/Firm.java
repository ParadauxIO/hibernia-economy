package io.paradaux.business.model;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Value;

/** Immutable snapshot returned by the Business API. */
@Value
public class Firm {

    private Integer firmId;
    private String displayName;
    private UUID proprietorUuid;
    private String discordUrl;
    private String hqRegion;
    private Integer defaultAccountId;
    private Boolean archived;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
