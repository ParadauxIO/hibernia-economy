package io.paradaux.business.model.internal;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class Firm {

    private Integer firmId;
    private String displayName;
    private String proprietorUuid;
    private String discordUrl;
    private String hqRegion;
    private Integer defaultAccountId;
    private Boolean archived;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
