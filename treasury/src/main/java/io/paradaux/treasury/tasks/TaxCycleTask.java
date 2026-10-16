package io.paradaux.treasury.tasks;

import lombok.extern.slf4j.Slf4j;
import io.paradaux.treasury.api.TaxApi;
import io.paradaux.treasury.event.TaxCycleEvent;
import io.paradaux.treasury.model.tax.TaxCycleReport;
import io.paradaux.treasury.model.tax.TaxCycleType;
import io.paradaux.treasury.services.TaxCycleRegistry;
import io.paradaux.treasury.services.TaxWebhookService;
import io.paradaux.treasury.utils.TaxCycleSchedule;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;

/**
 * Schedules and fires {@link TaxCycleEvent} for a single {@link TaxCycleType}.
 *
 * <p>Treasury creates one instance per enabled cycle type on startup. Each task
 * waits until the next occurrence of its configured fire time, then fires the
 * Bukkit event and reschedules itself for the next period.
 *
 * <p>This class is only the Bukkit glue (scheduler, event bus, webhook). All the
 * calendar maths — next fire time, tick delay, period start — lives in
 * {@link TaxCycleSchedule}, where it is unit- and mutation-tested.
 *
 * <p>Consuming plugins listen for {@link TaxCycleEvent} and call
 * {@link TaxApi#collectTax} or {@link TaxApi#collectBatch} inside the handler.
 * Treasury only drives the schedule.
 */
@Slf4j
public class TaxCycleTask extends BukkitRunnable {

    private final Plugin plugin;
    private final TaxApi taxApi;
    private final TaxCycleRegistry cycleRegistry;
    private final TaxWebhookService webhookService;
    private final TaxCycleSchedule schedule;
    private final Clock clock;

    /** The fire time this instance was scheduled for; the period it collects for. */
    private Instant scheduledFire;

    private TaxCycleTask(Plugin plugin, TaxApi taxApi, TaxCycleRegistry cycleRegistry,
                         TaxWebhookService webhookService, TaxCycleSchedule schedule, Clock clock) {
        this.plugin         = plugin;
        this.taxApi         = taxApi;
        this.cycleRegistry  = cycleRegistry;
        this.webhookService = webhookService;
        this.schedule       = schedule;
        this.clock          = clock;
    }

    // ---- Factory methods ----

    public static TaxCycleTask daily(Plugin plugin, TaxApi taxApi, TaxCycleRegistry registry,
                                     TaxWebhookService webhookService, int fireHour) {
        return new TaxCycleTask(plugin, taxApi, registry, webhookService,
                TaxCycleSchedule.daily(fireHour), Clock.systemDefaultZone());
    }

    public static TaxCycleTask weekly(Plugin plugin, TaxApi taxApi, TaxCycleRegistry registry,
                                      TaxWebhookService webhookService, int fireHour, int fireDayOfWeek) {
        return new TaxCycleTask(plugin, taxApi, registry, webhookService,
                TaxCycleSchedule.weekly(fireHour, fireDayOfWeek), Clock.systemDefaultZone());
    }

    public static TaxCycleTask monthly(Plugin plugin, TaxApi taxApi, TaxCycleRegistry registry,
                                       TaxWebhookService webhookService, int fireHour, int fireDayOfMonth) {
        return new TaxCycleTask(plugin, taxApi, registry, webhookService,
                TaxCycleSchedule.monthly(fireHour, fireDayOfMonth), Clock.systemDefaultZone());
    }

    // ---- Scheduling ----

    public void scheduleNext() {
        scheduleAfter(null);
    }

    private void scheduleAfter(Instant previousFire) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        ZonedDateTime next = schedule.nextFireAfter(now, previousFire);
        scheduledFire = next.toInstant();
        long delayTicks = TaxCycleSchedule.delayTicks(now, next);

        log.info("{} cycle scheduled — next fire {} (~{}s from now)",
                schedule.cycleType(), scheduledFire, delayTicks / 20);

        cycleRegistry.notifyNextFire(schedule.cycleType(), scheduledFire);
        runTaskLaterAsynchronously(plugin, delayTicks);
    }

    @Override
    public void run() {
        TaxCycleType cycleType = schedule.cycleType();
        Instant periodStart = TaxCycleSchedule.periodStart(
                scheduledFire != null ? scheduledFire : clock.instant());
        log.info("Firing {} tax cycle (period start: {})", cycleType, periodStart);

        cycleRegistry.startSession(cycleType, periodStart, false, null);

        TaxCycleEvent event = new TaxCycleEvent(cycleType, periodStart, taxApi);
        Bukkit.getPluginManager().callEvent(event);

        TaxCycleReport report = cycleRegistry.endSession();
        if (report != null) {
            log.info("{} cycle complete: collected={} skipped={} failed={} total={}",
                    cycleType, report.collectedCount(), report.skippedCount(),
                    report.failedCount(), report.totalCollected());
            webhookService.sendCycleReport(report);
        }

        TaxCycleTask next = new TaxCycleTask(plugin, taxApi, cycleRegistry, webhookService, schedule, clock);
        next.scheduleAfter(scheduledFire);
    }
}
