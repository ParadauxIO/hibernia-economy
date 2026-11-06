package io.paradaux.business.model;

import java.time.LocalDateTime;
import lombok.Value;

/** Immutable snapshot returned by the Business API. */
@Value
public class FirmAccount {
    private Integer firmId;
    private Integer accountId;
    private LocalDateTime addedAt;
}
