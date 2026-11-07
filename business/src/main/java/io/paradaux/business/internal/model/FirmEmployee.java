package io.paradaux.business.internal.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FirmEmployee {
    private Integer firmId;
    private String playerUuid;
    private String roleName;
    private LocalDateTime joinedAt;
    private LocalDateTime leftAt;
    private String addedBy;
    private String removedBy;
    private Boolean current;
}
