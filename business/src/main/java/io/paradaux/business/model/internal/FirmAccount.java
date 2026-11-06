package io.paradaux.business.model.internal;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FirmAccount {
    private Integer firmId;
    private Integer accountId;
    private LocalDateTime addedAt;
}
