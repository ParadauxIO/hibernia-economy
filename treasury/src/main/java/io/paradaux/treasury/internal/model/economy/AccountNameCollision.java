package io.paradaux.treasury.internal.model.economy;

import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Read-only evidence, including players without a personal account yet. */
@Getter
@Setter
public class AccountNameCollision {
    private int governmentId;
    private String governmentName;
    private UUID playerUuid;
    private Integer personalId;
    private Integer redirectId;
}
