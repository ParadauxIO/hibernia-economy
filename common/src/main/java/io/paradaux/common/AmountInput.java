package io.paradaux.common;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Validation for a money amount a player or admin typed into a command, shared
 * by treasury's {@code /pay}, {@code /eco}, {@code /gov}, {@code /treasury}
 * commands and business's firm deposit/withdraw/pay paths.
 *
 * <p>The command framework turns the typed word into a {@link BigDecimal} (it
 * strips {@code ,} grouping separators, then {@code new BigDecimal(word)}, so
 * scientific notation like {@code 1e3} parses and garbage like {@code NaN} never
 * reaches here). What arrives can therefore still be:
 * <ul>
 *   <li><b>sub-cent</b> — {@code 10.005}, or a European-style {@code 1.000,50}
 *       which becomes {@code 1.00050} once the comma is stripped. Rounding that
 *       to cents would silently move a different amount from the one typed (a
 *       thousandth of it, in the European case), so it is rejected;</li>
 *   <li><b>out of range</b> — anything the {@code DECIMAL(19,2)} ledger columns
 *       can't hold. {@code 1e999999999} is a tiny object but scaling it to cents
 *       materialises a billion-digit integer (minutes of CPU, then OOM), so the
 *       magnitude is checked <em>before</em> any scaling.</li>
 * </ul>
 *
 * <p>Accepted values are returned at scale 2, ready for the ledger.
 */
public final class AmountInput {

    /** Cents: the ledger's fractional digits. */
    public static final int SCALE = 2;

    /** The largest magnitude a {@code DECIMAL(19,2)} ledger column holds. */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999999999999.99");

    /** {@code DECIMAL(19,2)} leaves 17 digits before the decimal point. */
    private static final int MAX_INTEGER_DIGITS = 17;

    private AmountInput() {}

    /**
     * A strictly positive amount of at most two decimal places that fits the
     * ledger — the rule for paying, giving, taking and transferring money.
     */
    public static Optional<BigDecimal> positive(BigDecimal raw) {
        return exactCents(raw).filter(v -> v.signum() > 0);
    }

    /**
     * Like {@link #positive} but zero is allowed — the rule for setting a balance
     * (zeroing an account is a legitimate admin action).
     */
    public static Optional<BigDecimal> nonNegative(BigDecimal raw) {
        return exactCents(raw).filter(v -> v.signum() >= 0);
    }

    private static Optional<BigDecimal> exactCents(BigDecimal raw) {
        if (raw == null || integerDigits(raw) > MAX_INTEGER_DIGITS) {
            return Optional.empty();
        }
        // stripTrailingZeros first: 10.000 is a whole amount, not a sub-cent one,
        // and a zero with a huge scale (0E-999999999) collapses to plain 0 instead
        // of being rescaled digit by digit.
        BigDecimal stripped = raw.stripTrailingZeros();
        if (stripped.scale() > SCALE) {
            return Optional.empty();
        }
        return Optional.of(stripped.setScale(SCALE));
    }

    /** Digits before the decimal point, computed without materialising the value. */
    private static long integerDigits(BigDecimal v) {
        return v.signum() == 0 ? 0 : (long) v.precision() - v.scale();
    }
}
