package io.paradaux.business.internal.model.directdebit;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Optional;

/**
 * Calendar period a mandate's cap and recurring charge apply to (PAR-557). All
 * boundaries are UTC wall-clock times: days start at midnight, weeks on Monday,
 * months on the 1st.
 */
public enum DirectDebitPeriod {
    DAILY,
    WEEKLY,
    MONTHLY;

    /** Start of the period containing {@code utcNow}. */
    public LocalDateTime start(LocalDateTime utcNow) {
        LocalDateTime midnight = utcNow.toLocalDate().atStartOfDay();
        return switch (this) {
            case DAILY -> midnight;
            case WEEKLY -> midnight.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTHLY -> midnight.withDayOfMonth(1);
        };
    }

    /** Start of the period after the one containing {@code utcNow}. */
    public LocalDateTime nextStart(LocalDateTime utcNow) {
        LocalDateTime start = start(utcNow);
        return switch (this) {
            case DAILY -> start.plusDays(1);
            case WEEKLY -> start.plusWeeks(1);
            case MONTHLY -> start.plusMonths(1);
        };
    }

    /** Parses a command argument such as {@code weekly}; empty when unrecognised. */
    public static Optional<DirectDebitPeriod> parse(String raw) {
        if (raw == null) return Optional.empty();
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
