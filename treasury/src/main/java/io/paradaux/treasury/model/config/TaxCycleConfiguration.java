package io.paradaux.treasury.model.config;

import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationComponent;
import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;
import lombok.Getter;

/**
 * Configuration for Treasury's scheduled tax cycle events.
 *
 * <p>Each cycle type can be independently enabled or disabled. When enabled,
 * Treasury fires a {@link io.paradaux.treasury.event.TaxCycleEvent} at
 * the configured server-local hour. Consuming plugins listen for this event
 * and collect their own taxes inside the handler.
 *
 * <p>Config location: {@code config.yml} under the {@code tax.cycles:} key.
 */
@ConfigurationComponent
@Getter
public class TaxCycleConfiguration {

    // Framework reload writes these fields in place; publish each value to async readers.

    // ---- Daily cycle ----

    @ConfigurationValue(path = "tax.cycles.daily.enabled", defaultValue = "true")
    private volatile boolean dailyEnabled;

    /** Server-local hour (0–23) at which the daily cycle fires. */
    @ConfigurationValue(path = "tax.cycles.daily.hour", defaultValue = "3")
    private volatile int dailyHour;

    // ---- Weekly cycle ----

    @ConfigurationValue(path = "tax.cycles.weekly.enabled", defaultValue = "true")
    private volatile boolean weeklyEnabled;

    /** Server-local hour (0–23) at which the weekly cycle fires. */
    @ConfigurationValue(path = "tax.cycles.weekly.hour", defaultValue = "3")
    private volatile int weeklyHour;

    /**
     * Day of the week on which the weekly cycle fires (1 = Monday … 7 = Sunday,
     * following ISO-8601 convention).
     */
    @ConfigurationValue(path = "tax.cycles.weekly.day-of-week", defaultValue = "1")
    private volatile int weeklyDayOfWeek;

    // ---- Monthly cycle ----

    @ConfigurationValue(path = "tax.cycles.monthly.enabled", defaultValue = "true")
    private volatile boolean monthlyEnabled;

    /** Server-local hour (0–23) at which the monthly cycle fires. */
    @ConfigurationValue(path = "tax.cycles.monthly.hour", defaultValue = "3")
    private volatile int monthlyHour;

    /** Day of the month (1–28) on which the monthly cycle fires. */
    @ConfigurationValue(path = "tax.cycles.monthly.day-of-month", defaultValue = "1")
    private volatile int monthlyDayOfMonth;
}
