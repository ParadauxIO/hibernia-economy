package io.paradaux.treasuryrestapi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FirmEmployee {
    private UUID playerUuid;
    /** Current IGN from economy_players; null if the player is not in the registry. */
    private String playerName;
    private String roleName;
    private LocalDateTime joinedAt;
}
