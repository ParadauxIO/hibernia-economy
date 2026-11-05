package io.paradaux.business.model;

import io.paradaux.hibernia.framework.models.HiberniaPlayer;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Value;
import org.jetbrains.annotations.Nullable;

/** Immutable snapshot returned by the Business API. */
@Value
public class FirmPlayer implements HiberniaPlayer {
    @Nullable
    private UUID playerUuid;

    private String currentName;
    private String nameLower;
    private LocalDateTime firstSeen;
    private LocalDateTime lastSeen;

    /** Returns the typed player UUID, or null when the stored UUID was missing or malformed. */
    @Nullable
    @Override
    public UUID getUniqueId() {
        return playerUuid;
    }
}
