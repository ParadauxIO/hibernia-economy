package io.paradaux.treasury.model.economy;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;
import lombok.With;

@Builder(toBuilder = true)
@Value
@With
@AllArgsConstructor
public class AccountBalance {
    private int accountId;
    private BigDecimal balance;
    private long version;
}
