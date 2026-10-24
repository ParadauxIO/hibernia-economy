package io.paradaux.treasury.model.economy;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Summed balance for one account type — one row of the economy summary. */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AccountTypeTotal {
    private AccountType accountType;
    private BigDecimal total;
}
