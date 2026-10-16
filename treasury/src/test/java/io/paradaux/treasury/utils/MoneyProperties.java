package io.paradaux.treasury.utils;

import io.paradaux.treasury.mappers.AccountMapper;
import io.paradaux.treasury.mappers.MembershipMapper;
import io.paradaux.treasury.services.cache.AccountRedirectCache;
import io.paradaux.treasury.services.cache.PersonalAccountCache;
import io.paradaux.treasury.services.impl.AccountServiceImpl;
import io.paradaux.treasury.testsupport.TestConfigs;
import io.paradaux.hibernia.testsupport.properties.FixedSeedInCi;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.Scale;
import net.jqwik.api.lifecycle.AddLifecycleHook;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParseException;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Properties of {@link Money}: the 2-dp, HALF_EVEN currency normalisation every
 * command and the ledger floor check apply, the amount validators, and the
 * format → parse round trip of {@code AccountServiceImpl.formatAmount}.
 *
 * <p>Seeds: fixed on CI ({@link FixedSeedInCi}), random locally so developer runs keep
 * exploring; a failing seed is recorded in jqwik's failure database
 * ({@code build/jqwik-database}) and replayed and shrunk first on the next run.
 */
@AddLifecycleHook(FixedSeedInCi.class)
class MoneyProperties {

    /** DECIMAL(19,2): 17 integer digits. Every money column in the schema uses it. */
    static final BigDecimal COLUMN_MAX = new BigDecimal("99999999999999999.99");

    @Property
    void normalize_alwaysHasScaleTwo_andMovesAtMostHalfACent(@ForAll("anyAmount") BigDecimal v) {
        BigDecimal n = Money.normalize(v);
        assertThat(n.scale()).isEqualTo(2);
        assertThat(n.subtract(v).abs()).isLessThanOrEqualTo(new BigDecimal("0.005"));
    }

    @Property
    void normalize_isIdempotent(@ForAll("anyAmount") BigDecimal v) {
        BigDecimal once = Money.normalize(v);
        assertThat(Money.normalize(once)).isEqualTo(once);
    }

    @Property
    void normalize_isSignSymmetric(@ForAll("anyAmount") BigDecimal v) {
        // HALF_EVEN is symmetric about zero, so a debit and the matching credit of the
        // same raw amount always normalise to exact negatives: postings can't drift.
        assertThat(Money.normalize(v.negate())).isEqualByComparingTo(Money.normalize(v).negate());
    }

    @Property
    void normalize_leavesCentAmountsUntouched(@ForAll @Scale(2) @BigRange(min = "-1e17", max = "1e17") BigDecimal v) {
        assertThat(Money.normalize(v)).isEqualByComparingTo(v);
    }

    @Property
    void normalize_breaksExactHalfCentTiesToEven(@ForAll("cents") long cents) {
        // x.xx5 exactly: banker's rounding picks the even cent, so ties don't bias upward.
        BigDecimal tie = BigDecimal.valueOf(cents, 2).add(new BigDecimal("0.005"));
        BigDecimal n = Money.normalize(tie);
        long roundedCents = n.movePointRight(2).longValueExact();
        assertThat(Math.floorMod(roundedCents, 2)).isZero();
        assertThat(roundedCents).isIn(cents, cents + 1);
    }

    @Property
    void normalize_ofSumIsWithinACentOfSumOfNormals(@ForAll("anyAmount") BigDecimal a, @ForAll("anyAmount") BigDecimal b) {
        // Rounding is not additive — callers must normalise once, then post the same
        // normalised value on both legs (which LedgerServiceImpl does).
        BigDecimal diff = Money.normalize(a.add(b)).subtract(Money.normalize(a).add(Money.normalize(b))).abs();
        assertThat(diff).isLessThanOrEqualTo(new BigDecimal("0.01"));
    }

    @Property
    void requireValidAmount_acceptsExactlyPositiveCentAmounts(@ForAll("anyAmount") BigDecimal v) {
        boolean valid = v.stripTrailingZeros().scale() <= 2 && v.compareTo(Money.MINIMUM_AMOUNT) >= 0;
        if (valid) {
            Money.requireValidAmount(v);
            Money.requirePositive(v, "amount > 0");
        } else {
            assertThatThrownBy(() -> Money.requireValidAmount(v)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Money.requirePositive(v, "amount > 0")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Property
    void requireValidScale_rejectsSubCentPrecisionButAllowsZero(@ForAll("anyAmount") BigDecimal v) {
        if (v.stripTrailingZeros().scale() <= 2) {
            Money.requireValidScale(v);
        } else {
            assertThatThrownBy(() -> Money.requireValidScale(v)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Example
    void requireValidScale_acceptsZeroAndTrailingZeros() {
        Money.requireValidScale(BigDecimal.ZERO);
        Money.requireValidScale(new BigDecimal("100.0000"));
        Money.requireValidScale(new BigDecimal("1E+3")); // negative scale: a whole amount
    }

    @Property
    void formatAmount_thenParse_roundTripsToTheNormalisedValue(@ForAll("anyAmount") BigDecimal v) throws ParseException {
        String formatted = accountService().formatAmount(v);

        // Parse with the same configured pattern ("$#,##0.00") in BigDecimal mode, the way
        // a consumer of the displayed amount would; it must recover the 2-dp value exactly
        // — no float detour, no grouping or sign mangling, even at 17 integer digits.
        DecimalFormat parser = new DecimalFormat(TestConfigs.economy().getEconomyFormat(),
                DecimalFormatSymbols.getInstance(Locale.getDefault()));
        parser.setParseBigDecimal(true);
        BigDecimal parsed = (BigDecimal) parser.parse(formatted);
        assertThat(parsed).isEqualByComparingTo(Money.normalize(v));
    }

    @Property
    void plainString_roundTripsExactly(@ForAll("anyAmount") BigDecimal v) {
        BigDecimal n = Money.normalize(v);
        assertThat(new BigDecimal(n.toPlainString())).isEqualTo(n);
    }

    // ---- generators ----

    /**
     * Amounts across the full DECIMAL(19,2) range and beyond its precision: random
     * unscaled values at scales 0..6 (so sub-cent inputs appear), both signs, with the
     * edge cases (0, ±0.005 ties, ±0.01, the column maximum) mixed in.
     */
    @Provide
    Arbitrary<BigDecimal> anyAmount() {
        Arbitrary<BigDecimal> random = Arbitraries.integers().between(0, 6).flatMap(scale -> {
            // |unscaled| / 10^scale <= COLUMN_MAX, so every value fits the column.
            BigInteger bound = COLUMN_MAX.movePointRight(scale).toBigInteger();
            return Arbitraries.bigIntegers().between(bound.negate(), bound)
                    .map(unscaled -> new BigDecimal(unscaled, scale));
        });
        Arbitrary<BigDecimal> edges = Arbitraries.of(
                BigDecimal.ZERO, new BigDecimal("0.005"), new BigDecimal("-0.005"), new BigDecimal("0.015"),
                new BigDecimal("0.01"), new BigDecimal("-0.01"), new BigDecimal("0.0049999"),
                COLUMN_MAX, COLUMN_MAX.negate(), new BigDecimal("12345.675"));
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(9, random),
                net.jqwik.api.Tuple.of(1, edges));
    }

    @Provide
    Arbitrary<Long> cents() {
        return Arbitraries.longs().between(-9_999_999_999_999_999L, 9_999_999_999_999_998L);
    }

    private static AccountServiceImpl accountService() {
        return new AccountServiceImpl(mock(AccountMapper.class), mock(MembershipMapper.class),
                mock(AccountRedirectCache.class), mock(PersonalAccountCache.class), TestConfigs.economy());
    }
}
