package io.paradaux.business.internal.model.directdebit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DirectDebitPeriodTest {

    @ParameterizedTest
    @CsvSource({
        // period, now,                 start,               next start
        "DAILY,   2026-11-04T23:59:59, 2026-11-04T00:00,    2026-11-05T00:00",
        "DAILY,   2026-11-05T00:00,    2026-11-05T00:00,    2026-11-06T00:00",
        "WEEKLY,  2026-11-04T10:00,    2026-11-02T00:00,    2026-11-09T00:00", // Wednesday → Monday
        "WEEKLY,  2026-11-02T00:00,    2026-11-02T00:00,    2026-11-09T00:00", // Monday itself
        "WEEKLY,  2026-11-08T23:59,    2026-11-02T00:00,    2026-11-09T00:00", // Sunday
        "MONTHLY, 2026-11-30T12:00,    2026-11-01T00:00,    2026-12-01T00:00",
        "MONTHLY, 2026-12-31T23:59,    2026-12-01T00:00,    2027-01-01T00:00", // year rollover
        "MONTHLY, 2028-02-29T08:00,    2028-02-01T00:00,    2028-03-01T00:00", // leap day
    })
    void boundaries(DirectDebitPeriod period, LocalDateTime now, LocalDateTime start, LocalDateTime next) {
        assertThat(period.start(now)).isEqualTo(start);
        assertThat(period.nextStart(now)).isEqualTo(next);
        // The next period starts where it says it does.
        assertThat(period.start(next)).isEqualTo(next);
    }

    @ParameterizedTest
    @CsvSource({"weekly,WEEKLY", " Daily ,DAILY", "MONTHLY,MONTHLY"})
    void parsesCommandArguments(String raw, DirectDebitPeriod expected) {
        assertThat(DirectDebitPeriod.parse(raw)).contains(expected);
    }

    @ParameterizedTest
    @CsvSource({"fortnightly", "''", "yearly"})
    void rejectsUnknownPeriods(String raw) {
        assertThat(DirectDebitPeriod.parse(raw)).isEmpty();
    }
}
