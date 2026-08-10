package io.paradaux.treasury.commands;

import com.google.inject.Inject;
import io.paradaux.hibernia.framework.commander.annotations.*;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasury.api.TaxApi;
import io.paradaux.treasury.event.TaxCycleEvent;
import io.paradaux.treasury.model.config.TaxCycleConfiguration;
import io.paradaux.treasury.model.tax.TaxCycleReport;
import io.paradaux.treasury.model.tax.TaxCycleType;
import io.paradaux.treasury.services.TaxCycleRegistry;
import io.paradaux.treasury.services.TaxWebhookService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.RegisteredListener;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Admin command for inspecting tax cycle status and triggering cycles for testing.
 *
 * <pre>
 *   /tax status            — overview of cycles, integrations, next fire times
 *   /tax trigger &lt;cycle&gt;  — fire a TaxCycleEvent immediately (testing only)
 * </pre>
 *
 * Both subcommands require {@code treasury.admin.tax}.
 */
@Command({"tax"})
@Permission("treasury.admin.tax")
public class TaxCommand implements CommandHandler {

    private static final DateTimeFormatter FIRE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final TaxApi taxApi;
    private final TaxCycleRegistry cycleRegistry;
    private final TaxCycleConfiguration cycleConfig;
    private final TaxWebhookService webhookService;
    private final Message message;

    @Inject
    public TaxCommand(TaxApi taxApi, TaxCycleRegistry cycleRegistry,
                      TaxCycleConfiguration cycleConfig, TaxWebhookService webhookService,
                      Message message) {
        this.taxApi            = taxApi;
        this.cycleRegistry     = cycleRegistry;
        this.cycleConfig       = cycleConfig;
        this.webhookService    = webhookService;
        this.message           = message;
    }

    // ---- help ----

    @Route("")
    @Description("Show /tax help")
    public void root(@Sender CommandSender sender) {
        message.send(sender, "treasury.help.tax");
    }

    @Route("help")
    @Description("Show /tax help")
    public void help(@Sender CommandSender sender) {
        message.send(sender, "treasury.help.tax");
    }

    // ---- /tax status ----

    @Route("status")
    @Async
    @Description("Show all tax cycle configuration and registered plugin integrations")
    public void status(@Sender CommandSender sender) {
        message.send(sender, "treasury.tax.status.header");
        sender.sendMessage(Component.empty());

        // Default tax account
        message.send(sender, "treasury.tax.status.default-account",
                "name", taxApi.getDefaultTaxAccountName(),
                "id", taxApi.getDefaultTaxAccountId());
        sender.sendMessage(Component.empty());

        // Collect all listeners and the explicitly registered ones
        Set<String> allListeners = collectListenerPlugins();
        message.send(sender, "treasury.tax.status.cycles-header");

        for (TaxCycleType type : TaxCycleType.values()) {
            message.send(sender, "treasury.tax.status.cycle-row",
                    "type", type.name(),
                    "status", Message.rich(taxApi.isCycleEnabled(type)
                            ? "<green>enabled</green>" : "<red>disabled</red>"),
                    "schedule", formatScheduleHint(type),
                    "next", Message.rich(formatNextFire(taxApi.getNextFireTime(type))));

            Set<String> registered = taxApi.getCycleParticipants(type);
            if (!registered.isEmpty()) {
                message.send(sender, "treasury.tax.status.cycle-plugins",
                        "plugins", Message.rich(String.join(
                                "<gray>, </#6f6fff><#6f6fff>", registered)));
            } else {
                message.send(sender, "treasury.tax.status.cycle-plugins-none");
            }
        }

        // Show any listeners that haven't called registerCycleParticipant
        Set<String> allRegistered = new LinkedHashSet<>();
        for (TaxCycleType type : TaxCycleType.values()) {
            allRegistered.addAll(taxApi.getCycleParticipants(type));
        }
        Set<String> unregistered = new LinkedHashSet<>(allListeners);
        unregistered.removeAll(allRegistered);

        sender.sendMessage(Component.empty());
        if (!unregistered.isEmpty()) {
            message.send(sender, "treasury.tax.status.unregistered-header");
            for (String name : unregistered) {
                message.send(sender, "treasury.tax.status.unregistered-entry", "name", name);
            }
        } else if (allListeners.isEmpty()) {
            message.send(sender, "treasury.tax.status.no-listeners");
        }
    }

    // ---- /tax trigger <cycle> ----

    @Route("trigger <cycle>")
    @Async
    @Description("Immediately fire a tax cycle event for testing (uses Instant.now() as period start)")
    public void trigger(@Sender CommandSender sender, @Arg("cycle") String cycleArg) {
        TaxCycleType cycleType;
        try {
            cycleType = TaxCycleType.valueOf(cycleArg.toUpperCase());
        } catch (IllegalArgumentException e) {
            message.send(sender, "treasury.tax.unknown-cycle", "cycle", cycleArg);
            return;
        }

        Instant periodStart = Instant.now();
        String triggeredBy = sender.getName();

        message.send(sender, "treasury.tax.trigger.firing",
                "cycle", cycleType.name().toLowerCase(),
                "start", periodStart);
        message.send(sender, "treasury.tax.trigger.dedup-note");

        cycleRegistry.startSession(cycleType, periodStart, true, triggeredBy);

        TaxCycleEvent event = new TaxCycleEvent(cycleType, periodStart, taxApi);
        Bukkit.getPluginManager().callEvent(event);

        TaxCycleReport report = cycleRegistry.endSession();
        if (report != null) webhookService.sendCycleReport(report);

        if (report != null) {
            message.send(sender, "treasury.tax.trigger.done-report",
                    "collected", report.collectedCount(),
                    "skipped", report.skippedCount(),
                    "failed", report.failedCount());
        } else {
            message.send(sender, "treasury.tax.trigger.done");
        }
    }

    // ---- Helpers ----

    /** Collects the names of all plugins that have registered a listener for TaxCycleEvent. */
    private Set<String> collectListenerPlugins() {
        Set<String> names = new LinkedHashSet<>();
        for (RegisteredListener rl : TaxCycleEvent.getHandlerList().getRegisteredListeners()) {
            String pluginName = rl.getPlugin().getName();
            if (!pluginName.equalsIgnoreCase("Treasury")) {
                names.add(pluginName);
            }
        }
        return names;
    }

    private String formatNextFire(Optional<Instant> nextFire) {
        if (nextFire.isEmpty()) return "<gray>—</gray>";
        Instant next = nextFire.get();
        long secs = Duration.between(Instant.now(), next).getSeconds();
        String rel = secs <= 0 ? "imminent" : formatDuration(secs);
        return "<gray>next: <white>" + FIRE_FMT.format(next) + " <gray>(" + rel + ")";
    }

    private String formatDuration(long totalSeconds) {
        long days    = totalSeconds / 86400;
        long hours   = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600)  / 60;

        if (days > 0)   return days + "d " + hours + "h";
        if (hours > 0)  return hours + "h " + minutes + "m";
        return minutes + "m";
    }

    private String formatScheduleHint(TaxCycleType type) {
        return switch (type) {
            case DAILY   -> "daily @ " + cycleConfig.getDailyHour() + ":00";
            case WEEKLY  -> "weekly " + dayName(cycleConfig.getWeeklyDayOfWeek())
                           + " @ " + cycleConfig.getWeeklyHour() + ":00";
            case MONTHLY -> "monthly day-" + cycleConfig.getMonthlyDayOfMonth()
                           + " @ " + cycleConfig.getMonthlyHour() + ":00";
        };
    }

    private static String dayName(int isoDayOfWeek) {
        return switch (isoDayOfWeek) {
            case 1 -> "Mon";
            case 2 -> "Tue";
            case 3 -> "Wed";
            case 4 -> "Thu";
            case 5 -> "Fri";
            case 6 -> "Sat";
            case 7 -> "Sun";
            default -> "day-" + isoDayOfWeek;
        };
    }
}
