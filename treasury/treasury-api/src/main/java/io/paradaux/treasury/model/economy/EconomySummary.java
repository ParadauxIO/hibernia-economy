package io.paradaux.treasury.model.economy;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Top-level money supply broken down by account type — the in-game counterpart
 * to the explorer's total-supply view. Mirrors that view's scope: active
 * (non-archived) PERSONAL / BUSINESS / GOVERNMENT balances only, SYSTEM excluded.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class EconomySummary {
    private BigDecimal personal;
    private BigDecimal business;
    private BigDecimal government;

    /** Grand total across the three real account types. */
    public BigDecimal getTotal() {
        return nz(personal).add(nz(business)).add(nz(government));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
