package io.paradaux.treasury.model.economy;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;
import lombok.With;

/** Summed balance for one account type — one row of the economy summary. */
@Builder(toBuilder = true)
@Value
@With
@AllArgsConstructor
public class AccountTypeTotal {
    private AccountType accountType;
    private BigDecimal total;
}
