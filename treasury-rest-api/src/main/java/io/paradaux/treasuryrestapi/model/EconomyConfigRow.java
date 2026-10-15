package io.paradaux.treasuryrestapi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** One published setting from {@code economy_config} (economy-flyway V32). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EconomyConfigRow {
    private String configKey;
    private String valueType;
    private String configValue;
    private LocalDateTime updatedAt;
}
