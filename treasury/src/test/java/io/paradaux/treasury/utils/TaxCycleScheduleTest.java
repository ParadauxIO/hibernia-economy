package io.paradaux.treasury.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.paradaux.treasury.model.tax.TaxCycleType;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The tax-cycle calendar maths: when players are taxed next. Every case pins a
 * concrete wall-clock answer, including the DST, leap-year and month-length
 * edges where an off-by-one taxes a period twice or skips it.
 */
class TaxCycleScheduleTest {

    private static final ZoneId DUBLIN = ZoneId.of("Europe/Dublin");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final ZoneId UTC = ZoneOffset.UTC;

    private static ZonedDateTime at(String localDateTime, ZoneId zone) {
        return LocalDateTime.parse(localDateTime).atZone(zone);
    }

    @Nested
    class Daily {

        private final TaxCycleSchedule schedule = TaxCycleSchedule.daily(3);

        @Test
        void beforeFireHour_firesLaterToday() {
            assertThat(schedule.nextFireAfter(at("2026-01-10T01:30", UTC))).isEqualTo(at("2026-01-10T03:00", UTC));
        }

        @Test
        void oneNanoBeforeFireTime_stillFiresToday() {
            assertThat(schedule.nextFireAfter(at("2026-01-10T02:59:59.999999999", UTC)))
                    .isEqualTo(at("2026-01-10T03:00", UTC));
        }

        @Test
        void exactlyAtFireTime_schedulesTomorrow_neverTheSameInstantAgain() {
            assertThat(schedule.nextFireAfter(at("2026-01-10T03:00", UTC))).isEqualTo(at("2026-01-11T03:00", UTC));
        }

        @Test
        void afterFireHour_firesTomorrow() {
            assertThat(schedule.nextFireAfter(at("2026-01-10T03:00:01", UTC))).isEqualTo(at("2026-01-11T03:00", UTC));
        }

        @Test
        void midnightAndLastHourOfTheDay() {
            assertThat(TaxCycleSchedule.daily(0).nextFireAfter(at("2026-01-10T00:00", UTC)))
                    .isEqualTo(at("2026-01-11T00:00", UTC));
            assertThat(TaxCycleSchedule.daily(23).nextFireAfter(at("2026-01-10T22:59", UTC)))
                    .isEqualTo(at("2026-01-10T23:00", UTC));
        }

        @Test
        void yearRollover() {
            assertThat(schedule.nextFireAfter(at("2026-12-31T04:00", UTC))).isEqualTo(at("2027-01-01T03:00", UTC));
        }

        @Test
        void springForwardDay_firesAtWallClockFireHour() {
            // Dublin 2026-03-29: 01:00 GMT -> 02:00 IST. Adding 3 elapsed hours to
            // midnight would land on 04:00 IST; the cycle must fire at 03:00 local.
            ZonedDateTime next = schedule.nextFireAfter(at("2026-03-28T12:00", DUBLIN));
            assertThat(next.toLocalDateTime()).isEqualTo(LocalDateTime.parse("2026-03-29T03:00"));
            assertThat(next.getOffset()).isEqualTo(ZoneOffset.ofHours(1));
            assertThat(next.toInstant()).isEqualTo(Instant.parse("2026-03-29T02:00:00Z"));
        }

        @Test
        void fallBackDay_firesAtWallClockFireHour() {
            // Dublin 2026-10-25: 02:00 IST -> 01:00 GMT (a 25-hour day). Midnight + 3h
            // would be 02:00 GMT; the cycle must fire at 03:00 local.
            ZonedDateTime next = schedule.nextFireAfter(at("2026-10-24T12:00", DUBLIN));
            assertThat(next.toLocalDateTime()).isEqualTo(LocalDateTime.parse("2026-10-25T03:00"));
            assertThat(next.toInstant()).isEqualTo(Instant.parse("2026-10-25T03:00:00Z"));
        }

        @Test
        void fireHourInsideTheDstGap_movesForwardByTheGap() {
            // New York 2026-03-08: 02:00 does not exist (clocks jump to 03:00 EDT).
            ZonedDateTime next = TaxCycleSchedule.daily(2).nextFireAfter(at("2026-03-07T12:00", NEW_YORK));
            assertThat(next.toLocalDateTime()).isEqualTo(LocalDateTime.parse("2026-03-08T03:00"));
            assertThat(next.toInstant()).isEqualTo(Instant.parse("2026-03-08T07:00:00Z"));
        }

        @Test
        void fireHourInsideTheRepeatedHour_firesOnce_atTheEarlierOffset() {
            // Dublin 2026-10-25 01:00 happens twice; the first (IST) occurrence fires,
            // and a task that ran then schedules the next day, not the repeat.
            TaxCycleSchedule oneAm = TaxCycleSchedule.daily(1);
            ZonedDateTime first = oneAm.nextFireAfter(at("2026-10-24T12:00", DUBLIN));
            assertThat(first.toInstant()).isEqualTo(Instant.parse("2026-10-25T00:00:00Z"));
            assertThat(oneAm.nextFireAfter(first).toLocalDate())
                    .isEqualTo(first.toLocalDate().plusDays(1));
        }

        @Test
        void fireHourIsInterpretedInTheZoneOfNow() {
            // Same instant, different server zones -> different fire instants.
            Instant instant = Instant.parse("2026-01-10T12:00:00Z");
            assertThat(schedule.nextFireAfter(instant.atZone(UTC)).toInstant())
                    .isEqualTo(Instant.parse("2026-01-11T03:00:00Z"));
            assertThat(schedule.nextFireAfter(instant.atZone(ZoneId.of("Asia/Tokyo")))
                            .toInstant())
                    .isEqualTo(Instant.parse("2026-01-10T18:00:00Z")); // 03:00 JST on the 11th
        }
    }

    @Nested
    class Weekly {

        // 2026-01-12 is a Monday.
        private final TaxCycleSchedule mondays = TaxCycleSchedule.weekly(3, 1);

        @Test
        void laterInTheWeek_firesNextMonday() {
            assertThat(mondays.nextFireAfter(at("2026-01-14T10:00", UTC))) // Wednesday
                    .isEqualTo(at("2026-01-19T03:00", UTC));
        }

        @Test
        void onTheFireDayBeforeTheHour_firesToday() {
            assertThat(mondays.nextFireAfter(at("2026-01-12T02:00", UTC))).isEqualTo(at("2026-01-12T03:00", UTC));
        }

        @Test
        void onTheFireDayAtOrAfterTheHour_firesNextWeek() {
            assertThat(mondays.nextFireAfter(at("2026-01-12T03:00", UTC))).isEqualTo(at("2026-01-19T03:00", UTC));
            assertThat(mondays.nextFireAfter(at("2026-01-12T23:00", UTC))).isEqualTo(at("2026-01-19T03:00", UTC));
        }

        @Test
        void dayBeforeTheFireDay_firesTomorrow() {
            assertThat(mondays.nextFireAfter(at("2026-01-11T23:00", UTC))) // Sunday
                    .isEqualTo(at("2026-01-12T03:00", UTC));
        }

        @Test
        void sundayCycle_fromMonday_isSixDaysOut() {
            assertThat(TaxCycleSchedule.weekly(3, 7).nextFireAfter(at("2026-01-12T10:00", UTC)))
                    .isEqualTo(at("2026-01-18T03:00", UTC));
        }

        @ParameterizedTest
        @CsvSource({
            // dayOfWeek, expected date — from Wednesday 2026-01-14 12:00
            "1, 2026-01-19",
            "2, 2026-01-20",
            "3, 2026-01-21",
            "4, 2026-01-15",
            "5, 2026-01-16",
            "6, 2026-01-17",
            "7, 2026-01-18",
        })
        void everyIsoDayOfWeek(int dayOfWeek, String expectedDate) {
            assertThat(TaxCycleSchedule.weekly(3, dayOfWeek).nextFireAfter(at("2026-01-14T12:00", UTC)))
                    .isEqualTo(at(expectedDate + "T03:00", UTC));
        }

        @Test
        void weekSpanningSpringForward_keepsTheWallClockHour() {
            // Sunday 2026-03-29 is the Dublin spring-forward day.
            ZonedDateTime next = TaxCycleSchedule.weekly(3, 7).nextFireAfter(at("2026-03-25T12:00", DUBLIN));
            assertThat(next.toLocalDateTime()).isEqualTo(LocalDateTime.parse("2026-03-29T03:00"));
            assertThat(next.toInstant()).isEqualTo(Instant.parse("2026-03-29T02:00:00Z"));
        }
    }

    @Nested
    class Monthly {

        @Test
        void beforeTheFireDay_firesThisMonth() {
            assertThat(TaxCycleSchedule.monthly(3, 15).nextFireAfter(at("2026-05-10T12:00", UTC)))
                    .isEqualTo(at("2026-05-15T03:00", UTC));
        }

        @Test
        void onTheFireDayBeforeTheHour_firesToday() {
            assertThat(TaxCycleSchedule.monthly(3, 15).nextFireAfter(at("2026-05-15T02:59", UTC)))
                    .isEqualTo(at("2026-05-15T03:00", UTC));
        }

        @Test
        void atTheFireTime_firesNextMonth() {
            assertThat(TaxCycleSchedule.monthly(3, 15).nextFireAfter(at("2026-05-15T03:00", UTC)))
                    .isEqualTo(at("2026-06-15T03:00", UTC));
        }

        @Test
        void decemberRollsIntoJanuary() {
            assertThat(TaxCycleSchedule.monthly(3, 1).nextFireAfter(at("2026-12-01T03:00", UTC)))
                    .isEqualTo(at("2027-01-01T03:00", UTC));
        }

        @Test
        void day31_clampsToShortMonths_andRecoversInLongOnes() {
            TaxCycleSchedule lastDay = TaxCycleSchedule.monthly(3, 31);
            // April has 30 days.
            assertThat(lastDay.nextFireAfter(at("2026-04-02T00:00", UTC))).isEqualTo(at("2026-04-30T03:00", UTC));
            // After Jan 31 fired: Feb 2026 has 28 days.
            ZonedDateTime feb = lastDay.nextFireAfter(at("2026-01-31T03:00", UTC));
            assertThat(feb).isEqualTo(at("2026-02-28T03:00", UTC));
            // ...and the month after a clamped firing goes back to the 31st, not the 28th.
            assertThat(lastDay.nextFireAfter(feb)).isEqualTo(at("2026-03-31T03:00", UTC));
        }

        @Test
        void leapYear_february29() {
            TaxCycleSchedule day30 = TaxCycleSchedule.monthly(3, 30);
            assertThat(day30.nextFireAfter(at("2028-02-10T00:00", UTC))).isEqualTo(at("2028-02-29T03:00", UTC));
            TaxCycleSchedule day29 = TaxCycleSchedule.monthly(3, 29);
            assertThat(day29.nextFireAfter(at("2028-02-29T03:00", UTC))).isEqualTo(at("2028-03-29T03:00", UTC));
            // Non-leap year: the 29th clamps to the 28th.
            assertThat(day29.nextFireAfter(at("2027-02-01T00:00", UTC))).isEqualTo(at("2027-02-28T03:00", UTC));
        }

        @Test
        void clampedDayAlreadyPassedThisMonth_firesNextMonth() {
            // Fire day 31 in a 30-day month, after Apr 30 03:00 -> May 31.
            assertThat(TaxCycleSchedule.monthly(3, 31).nextFireAfter(at("2026-04-30T04:00", UTC)))
                    .isEqualTo(at("2026-05-31T03:00", UTC));
        }

        @Test
        void monthSpanningFallBack_keepsTheWallClockHour() {
            ZonedDateTime next = TaxCycleSchedule.monthly(3, 25).nextFireAfter(at("2026-10-02T12:00", DUBLIN));
            assertThat(next.toLocalDateTime()).isEqualTo(LocalDateTime.parse("2026-10-25T03:00"));
            assertThat(next.toInstant()).isEqualTo(Instant.parse("2026-10-25T03:00:00Z"));
        }
    }

    @Nested
    class PreviousFireAnchor {

        private final TaxCycleSchedule schedule = TaxCycleSchedule.daily(3);

        @Test
        void taskRunBeforeItsFireTime_doesNotFireThatPeriodAgain() {
            // The scheduler ran the 03:00 task at 02:59:59.6; from `now` alone the
            // next fire would be 03:00 the same day — a second collection.
            ZonedDateTime early = at("2026-01-10T02:59:59.600", UTC);
            Instant previousFire = Instant.parse("2026-01-10T03:00:00Z");
            assertThat(schedule.nextFireAfter(early, previousFire)).isEqualTo(at("2026-01-11T03:00", UTC));
        }

        @Test
        void previousFireInThePast_schedulesFromNow() {
            // Server was suspended for two days: catch up with the next future fire,
            // not each missed one.
            Instant previousFire = Instant.parse("2026-01-10T03:00:00Z");
            assertThat(schedule.nextFireAfter(at("2026-01-12T12:00", UTC), previousFire))
                    .isEqualTo(at("2026-01-13T03:00", UTC));
        }

        @Test
        void previousFireExactlyNow_schedulesTheNextPeriod() {
            Instant previousFire = Instant.parse("2026-01-10T03:00:00Z");
            assertThat(schedule.nextFireAfter(at("2026-01-10T03:00", UTC), previousFire))
                    .isEqualTo(at("2026-01-11T03:00", UTC));
        }

        @Test
        void noPreviousFire_isPlainNextFire() {
            assertThat(schedule.nextFireAfter(at("2026-01-10T01:00", UTC), null))
                    .isEqualTo(at("2026-01-10T03:00", UTC));
        }

        @Test
        void resultKeepsTheZoneOfNow() {
            Instant previousFire = Instant.parse("2026-03-29T02:00:00Z"); // 03:00 IST
            ZonedDateTime next = schedule.nextFireAfter(at("2026-03-29T02:59", DUBLIN), previousFire);
            assertThat(next.getZone()).isEqualTo(DUBLIN);
            assertThat(next.toLocalDateTime()).isEqualTo(LocalDateTime.parse("2026-03-30T03:00"));
        }
    }

    @Nested
    class DelayTicks {

        private final ZonedDateTime now = at("2026-01-10T00:00", UTC);

        @Test
        void wholeHour_isExactTicks() {
            assertThat(TaxCycleSchedule.delayTicks(now, now.plusHours(1))).isEqualTo(72_000L);
        }

        @Test
        void partialTick_roundsUp_soTheTaskNeverRunsEarly() {
            assertThat(TaxCycleSchedule.delayTicks(now, now.plusHours(1).minusNanos(1_000_000)))
                    .isEqualTo(72_000L);
            assertThat(TaxCycleSchedule.delayTicks(now, now.plusHours(1).plusNanos(1_000_000)))
                    .isEqualTo(72_001L);
            assertThat(TaxCycleSchedule.delayTicks(now, now.plusNanos(1_050_000_000L)))
                    .isEqualTo(21L);
        }

        @Test
        void neverLessThanOneSecond() {
            assertThat(TaxCycleSchedule.delayTicks(now, now)).isEqualTo(20L);
            assertThat(TaxCycleSchedule.delayTicks(now, now.plusNanos(999_000_000L)))
                    .isEqualTo(20L);
            assertThat(TaxCycleSchedule.delayTicks(now, now.plusSeconds(1))).isEqualTo(20L);
            assertThat(TaxCycleSchedule.delayTicks(now, now.minusSeconds(30))).isEqualTo(20L);
        }

        @Test
        void acrossDst_countsElapsedTimeNotWallClock() {
            // 00:00 -> 03:00 local on the Dublin spring-forward day is 2 real hours.
            ZonedDateTime midnight = at("2026-03-29T00:00", DUBLIN);
            ZonedDateTime fire = TaxCycleSchedule.daily(3).nextFireAfter(midnight);
            assertThat(TaxCycleSchedule.delayTicks(midnight, fire)).isEqualTo(2 * 72_000L);
        }
    }

    @Nested
    class PeriodStart {

        @Test
        void isTheFireInstantTruncatedToTheHour() {
            assertThat(TaxCycleSchedule.periodStart(Instant.parse("2026-01-10T03:00:00Z")))
                    .isEqualTo(Instant.parse("2026-01-10T03:00:00Z"));
            assertThat(TaxCycleSchedule.periodStart(Instant.parse("2026-01-10T03:59:59.999Z")))
                    .isEqualTo(Instant.parse("2026-01-10T03:00:00Z"));
        }

        @Test
        void jitterAroundTheSameFire_givesTheSamePeriod_soIdempotencyKeysMatch() {
            Instant scheduled = at("2026-03-29T03:00", DUBLIN).toInstant();
            assertThat(TaxCycleSchedule.periodStart(scheduled.plusMillis(1_500)))
                    .isEqualTo(TaxCycleSchedule.periodStart(scheduled));
        }
    }

    @Nested
    class Validation {

        @ParameterizedTest
        @ValueSource(ints = {-1, 24, 100})
        void fireHourOutOfRange_isRejected(int hour) {
            assertThatThrownBy(() -> TaxCycleSchedule.daily(hour))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("fire hour")
                    .hasMessageContaining(String.valueOf(hour));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 8})
        void dayOfWeekOutOfRange_isRejected(int day) {
            assertThatThrownBy(() -> TaxCycleSchedule.weekly(3, day))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("day of week");
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 32})
        void dayOfMonthOutOfRange_isRejected(int day) {
            assertThatThrownBy(() -> TaxCycleSchedule.monthly(3, day))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("day of month");
        }

        @Test
        void boundaryValuesAreAccepted() {
            assertThat(TaxCycleSchedule.daily(0).cycleType()).isEqualTo(TaxCycleType.DAILY);
            assertThat(TaxCycleSchedule.daily(23).cycleType()).isEqualTo(TaxCycleType.DAILY);
            assertThat(TaxCycleSchedule.weekly(0, 1).cycleType()).isEqualTo(TaxCycleType.WEEKLY);
            assertThat(TaxCycleSchedule.weekly(23, 7).cycleType()).isEqualTo(TaxCycleType.WEEKLY);
            assertThat(TaxCycleSchedule.monthly(0, 1).cycleType()).isEqualTo(TaxCycleType.MONTHLY);
            assertThat(TaxCycleSchedule.monthly(23, 31).cycleType()).isEqualTo(TaxCycleType.MONTHLY);
        }
    }
}
