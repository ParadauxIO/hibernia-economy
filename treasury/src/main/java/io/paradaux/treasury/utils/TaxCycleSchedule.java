package io.paradaux.treasury.utils;

import io.paradaux.treasury.model.tax.TaxCycleType;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * The pure calendar maths behind the automatic tax cycles: when a cycle fires
 * next, how long to wait for it in server ticks, and which period a firing
 * belongs to.
 *
 * <p>Extracted from {@code TaxCycleTask} (Bukkit scheduling glue, outside the
 * coverage gate) so the logic that decides <em>when players are taxed</em> is
 * unit-tested and mutation-tested in isolation, with the clock passed in rather
 * than read from the system.
 *
 * <p>Fire times are <b>wall-clock</b> times in the supplied zone: a cycle
 * configured for 03:00 fires at 03:00 local on every day, including DST
 * transition days. (The original implementation added {@code fireHour} elapsed
 * hours to local midnight, which fired an hour late on the spring-forward day
 * and an hour early on the fall-back day.) A fire time that falls in a DST gap
 * is moved forward by the gap length, per {@link ZonedDateTime} resolution.
 */
public final class TaxCycleSchedule {

    private static final long MILLIS_PER_TICK = 50L;
    /** Never schedule closer than one second out (the original lower bound). */
    private static final long MIN_DELAY_TICKS = 20L;

    private final TaxCycleType cycleType;
    private final int fireHour;
    private final int fireDayOfWeek; // ISO 1 = Monday … 7 = Sunday (weekly only)
    private final int fireDayOfMonth; // 1–31, clamped to the month's length (monthly only)

    private TaxCycleSchedule(TaxCycleType cycleType, int fireHour, int fireDayOfWeek, int fireDayOfMonth) {
        this.cycleType = Objects.requireNonNull(cycleType, "cycleType");
        this.fireHour = requireInRange("fire hour", fireHour, 0, 23);
        this.fireDayOfWeek = requireInRange("day of week", fireDayOfWeek, 1, 7);
        this.fireDayOfMonth = requireInRange("day of month", fireDayOfMonth, 1, 31);
    }

    public static TaxCycleSchedule daily(int fireHour) {
        return new TaxCycleSchedule(TaxCycleType.DAILY, fireHour, 1, 1);
    }

    public static TaxCycleSchedule weekly(int fireHour, int fireDayOfWeek) {
        return new TaxCycleSchedule(TaxCycleType.WEEKLY, fireHour, fireDayOfWeek, 1);
    }

    public static TaxCycleSchedule monthly(int fireHour, int fireDayOfMonth) {
        return new TaxCycleSchedule(TaxCycleType.MONTHLY, fireHour, 1, fireDayOfMonth);
    }

    public TaxCycleType cycleType() {
        return cycleType;
    }

    /**
     * The first fire time strictly after {@code now}, in {@code now}'s zone. A
     * fire time equal to {@code now} is treated as already passed, so a task that
     * fires exactly on time schedules the <em>next</em> period, never itself again.
     */
    public ZonedDateTime nextFireAfter(ZonedDateTime now) {
        return switch (cycleType) {
            case DAILY -> nextDaily(now);
            case WEEKLY -> nextWeekly(now);
            case MONTHLY -> nextMonthly(now);
        };
    }

    /**
     * The next fire time after both {@code now} and {@code previousFire} (the fire
     * time of the period that just ran, or {@code null} on first scheduling). If
     * the scheduler ran a task slightly before its fire time, scheduling from
     * {@code now} alone would pick that same fire time again and collect the
     * period twice; anchoring on the previous fire rules that out.
     */
    public ZonedDateTime nextFireAfter(ZonedDateTime now, Instant previousFire) {
        if (previousFire != null && previousFire.isAfter(now.toInstant())) {
            return nextFireAfter(previousFire.atZone(now.getZone()));
        }
        return nextFireAfter(now);
    }

    private ZonedDateTime nextDaily(ZonedDateTime now) {
        ZonedDateTime candidate = at(now, now.toLocalDate());
        return candidate.isAfter(now) ? candidate : at(now, now.toLocalDate().plusDays(1));
    }

    private ZonedDateTime nextWeekly(ZonedDateTime now) {
        int daysUntil = Math.floorMod(fireDayOfWeek - now.getDayOfWeek().getValue(), 7);
        LocalDate day = now.toLocalDate().plusDays(daysUntil);
        ZonedDateTime candidate = at(now, day);
        return candidate.isAfter(now) ? candidate : at(now, day.plusWeeks(1));
    }

    private ZonedDateTime nextMonthly(ZonedDateTime now) {
        ZonedDateTime candidate = at(now, clampedDay(now.toLocalDate()));
        if (candidate.isAfter(now)) {
            return candidate;
        }
        // Re-clamp against the NEXT month's length: day 31 fires on Feb 28/29,
        // then on Mar 31 again (not on Mar 28/29).
        return at(now, clampedDay(now.toLocalDate().withDayOfMonth(1).plusMonths(1)));
    }

    private LocalDate clampedDay(LocalDate inMonth) {
        return inMonth.withDayOfMonth(Math.min(fireDayOfMonth, inMonth.lengthOfMonth()));
    }

    private ZonedDateTime at(ZonedDateTime zoneSource, LocalDate day) {
        return day.atTime(fireHour, 0).atZone(zoneSource.getZone());
    }

    /**
     * Server ticks to wait from {@code now} until {@code next}, rounded <b>up</b>
     * to a whole tick so the task can never run before its fire time (rounding
     * down — as whole seconds did — could fire a fraction of a second early, in
     * the previous hour, and so stamp the cycle with the previous hour's period).
     * At least one second, matching the original lower bound.
     */
    public static long delayTicks(ZonedDateTime now, ZonedDateTime next) {
        long millis = Duration.between(now, next).toMillis();
        long ticks = Math.ceilDiv(millis, MILLIS_PER_TICK);
        return Math.max(MIN_DELAY_TICKS, ticks);
    }

    /**
     * The canonical period start for a firing: the scheduled fire instant,
     * truncated to the hour. Consumers derive their idempotency keys from it
     * (e.g. business balance tax), so it must identify the period rather than the
     * exact moment the scheduler happened to run the task.
     */
    public static Instant periodStart(Instant fireInstant) {
        return fireInstant.truncatedTo(ChronoUnit.HOURS);
    }

    private static int requireInRange(String what, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    "Tax cycle " + what + " must be between " + min + " and " + max + ", was " + value);
        }
        return value;
    }
}
