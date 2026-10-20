package io.paradaux.treasury.mappers;

import java.math.BigDecimal;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Per-player, per-period running totals for the progressive income tax
 * ({@code income_tax_period}, V36). Holds no logic: the service decides what is
 * income and how much tax it owes.
 */
public interface IncomeTaxMapper {

    /**
     * Ensures the player's row for the period exists and locks it until the
     * surrounding transaction ends, so concurrent payments to one player are taxed
     * one after another against an up-to-date total. The upsert locks a fresh row
     * too, which a plain {@code SELECT ... FOR UPDATE} could not.
     */
    @Insert("""
            INSERT INTO income_tax_period (owner_uuid_bin, period_start)
            VALUES (#{ownerUuid}, #{periodStart})
            ON DUPLICATE KEY UPDATE period_start = VALUES(period_start)
            """)
    void lockPeriod(@Param("ownerUuid") UUID ownerUuid, @Param("periodStart") long periodStart);

    /** Income recorded so far this period; call after {@link #lockPeriod} in the same transaction. */
    @Select("""
            SELECT income
              FROM income_tax_period
             WHERE owner_uuid_bin = #{ownerUuid} AND period_start = #{periodStart}
            """)
    BigDecimal readIncome(@Param("ownerUuid") UUID ownerUuid, @Param("periodStart") long periodStart);

    /** Adds one payment, and the tax taken on it, to the period's totals. */
    @Update("""
            UPDATE income_tax_period
               SET income = income + #{income},
                   tax_paid = tax_paid + #{tax}
             WHERE owner_uuid_bin = #{ownerUuid} AND period_start = #{periodStart}
            """)
    void addIncome(
            @Param("ownerUuid") UUID ownerUuid,
            @Param("periodStart") long periodStart,
            @Param("income") BigDecimal income,
            @Param("tax") BigDecimal tax);
}
