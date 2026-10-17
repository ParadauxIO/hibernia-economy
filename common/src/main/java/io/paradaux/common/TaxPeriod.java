package io.paradaux.common;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A repeating, fixed-length accounting period: every {@code length}, starting at
 * {@code anchor}. Used to reset cumulative counters such as the progressive income
 * tax's per-player income.
 *
 * <p>Periods are aligned to the anchor rather than to when a player first earned,
 * so every player's counter resets at the same moment. Moments before the anchor
 * fall into earlier periods (the arithmetic is floor-based), so a future anchor is
 * harmless.
 */
public final class TaxPeriod {

    /** Shortest period accepted; shorter periods would churn counter rows for no benefit. */
    public static final Duration MINIMUM_LENGTH = Duration.ofMinutes(1);

    private static final Pattern SIMPLE = Pattern.compile("(\\d+)\\s*([smhdw])");

    private final Duration length;
    private final Instant anchor;

    private TaxPeriod(Duration length, Instant anchor) {
        this.length = length;
        this.anchor = anchor;
    }

    /**
     * Creates a period of {@code length} repeating from {@code anchor}.
     *
     * @throws IllegalArgumentException if {@code length} is shorter than {@link #MINIMUM_LENGTH}
     */
    public static TaxPeriod of(Duration length, Instant anchor) {
        Objects.requireNonNull(length, "length");
        Objects.requireNonNull(anchor, "anchor");
        if (length.compareTo(MINIMUM_LENGTH) < 0) {
            throw new IllegalArgumentException(
                    "Tax period must be at least " + MINIMUM_LENGTH.toMinutes() + " minute, got " + length);
        }
        return new TaxPeriod(length, anchor);
    }

    /**
     * Parses a period length. Accepts a number and a unit ({@code s}, {@code m},
     * {@code h}, {@code d}, {@code w}), optionally repeated ({@code 1d12h}), or an
     * ISO-8601 duration ({@code P30D}, {@code PT12H}).
     *
     * @throws IllegalArgumentException if the text is blank or not a valid duration
     */
    public static Duration parseLength(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Tax period length is blank");
        }
        String s = text.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("p")) {
            try {
                return Duration.parse(s.toUpperCase(Locale.ROOT));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Invalid ISO-8601 tax period '" + text + "'", e);
            }
        }
        Matcher m = SIMPLE.matcher(s);
        Duration total = Duration.ZERO;
        int end = 0;
        while (m.find()) {
            if (m.start() != end && !s.substring(end, m.start()).isBlank()) {
                throw new IllegalArgumentException("Invalid tax period '" + text + "'");
            }
            long n = Long.parseLong(m.group(1));
            total = total.plus(
                    switch (m.group(2)) {
                        case "s" -> Duration.ofSeconds(n);
                        case "m" -> Duration.ofMinutes(n);
                        case "h" -> Duration.ofHours(n);
                        case "d" -> Duration.ofDays(n);
                        default -> Duration.ofDays(n * 7);
                    });
            end = m.end();
        }
        if (end == 0 || !s.substring(end).isBlank()) {
            throw new IllegalArgumentException(
                    "Invalid tax period '" + text + "' (use e.g. 30d, 2w, 12h, 1d12h or P30D)");
        }
        return total;
    }

    /**
     * Parses the anchor instant: an ISO-8601 instant ({@code 2026-01-01T00:00:00Z})
     * or a plain date ({@code 2026-01-01}, read as midnight UTC).
     *
     * @throws IllegalArgumentException if the text is blank or not a valid instant/date
     */
    public static Instant parseAnchor(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Tax period anchor is blank");
        }
        String s = text.trim();
        try {
            return s.length() == 10 ? Instant.parse(s + "T00:00:00Z") : Instant.parse(s);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Invalid tax period anchor '" + text + "' (use e.g. 2026-01-01 or 2026-01-01T00:00:00Z)", e);
        }
    }

    /** Start of the period containing {@code moment}. */
    public Instant periodStart(Instant moment) {
        long lengthMillis = length.toMillis();
        long offset = Math.floorDiv(moment.toEpochMilli() - anchor.toEpochMilli(), lengthMillis);
        return anchor.plusMillis(offset * lengthMillis);
    }

    /** Start of the period after the one containing {@code moment}, i.e. when its counter resets. */
    public Instant nextPeriodStart(Instant moment) {
        return periodStart(moment).plus(length);
    }

    public Duration length() {
        return length;
    }

    public Instant anchor() {
        return anchor;
    }
}
