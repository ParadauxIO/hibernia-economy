package io.paradaux.treasury.services.impl;

import com.google.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import io.paradaux.treasury.model.config.DiscordWebhookConfiguration;
import io.paradaux.treasury.model.tax.TaxCycleReport;
import io.paradaux.treasury.services.TaxWebhookService;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static io.paradaux.treasury.services.impl.DiscordWebhooks.escapeJson;
import static io.paradaux.treasury.services.impl.DiscordWebhooks.field;
import static io.paradaux.treasury.services.impl.DiscordWebhooks.formatMoney;

@Slf4j
public class TaxWebhookServiceImpl implements TaxWebhookService {

    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ISO_INSTANT;
    private static final DateTimeFormatter DISPLAY_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    // Discord embed colours
    private static final int COLOR_SCHEDULED = 0x57F287; // green
    private static final int COLOR_MANUAL    = 0xE67E22; // orange

    private final DiscordWebhookConfiguration config;
    private final HttpClient httpClient;

    @Inject
    public TaxWebhookServiceImpl(DiscordWebhookConfiguration config) {
        this.config = config;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public void sendCycleReport(TaxCycleReport report) {
        if (!config.isEnabled()) return;
        String url = config.getUrl();
        if (url == null || url.isBlank()) return;

        String payload = buildPayload(report);
        DiscordWebhooks.post(httpClient, url, payload, "Tax Webhook");
    }

    private String buildPayload(TaxCycleReport report) {
        int color = report.manual() ? COLOR_MANUAL : COLOR_SCHEDULED;

        String title = report.manual()
                ? "[MANUAL] " + report.cycleType().name() + " Tax Cycle"
                : report.cycleType().name() + " Tax Cycle Complete";

        // triggeredBy is JSON-escaped once when the whole description is serialised below.
        // Don't escape twice or special chars (\" \\) appear visibly mangled in the embed.
        String description = report.manual()
                ? "Manually triggered by **" + (report.triggeredBy() != null ? report.triggeredBy() : "unknown") + "**."
                : "Scheduled collection completed.";

        String footerText = "Period: " + DISPLAY_FMT.format(report.periodStart())
                + " UTC | Treasury" + (report.manual() ? " | Manual Run" : "");

        StringBuilder fields = new StringBuilder();

        // Total collected
        fields.append(field("💰 Total Collected", formatMoney(report.totalCollected()), false));
        fields.append(",");

        // By destination account
        fields.append(field("📊 By Destination", buildMapLines(report.byDestinationAccount()), true));
        fields.append(",");

        // By tax source
        fields.append(field("📋 By Tax Source", buildMapLines(report.byTaxType()), true));
        fields.append(",");

        // Stats
        String stats = "✅ " + formatCount(report.collectedCount()) + " collected"
                + "  ⏭ " + formatCount(report.skippedCount()) + " skipped"
                + "  ❌ " + formatCount(report.failedCount()) + " failed";
        fields.append(field("📈 Statistics", stats, false));

        return "{"
                + "\"embeds\":[{"
                + "\"title\":\"" + escapeJson(title) + "\","
                + "\"description\":\"" + escapeJson(description) + "\","
                + "\"color\":" + color + ","
                + "\"fields\":[" + fields + "],"
                + "\"footer\":{\"text\":\"" + escapeJson(footerText) + "\"},"
                + "\"timestamp\":\"" + ISO_FMT.format(report.periodStart()) + "\""
                + "}]}";
    }

    private static String buildMapLines(Map<String, BigDecimal> map) {
        if (map == null || map.isEmpty()) return "—";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, BigDecimal> entry : map.entrySet()) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("**").append(entry.getKey()).append("**: +").append(formatMoney(entry.getValue()));
        }
        return sb.toString();
    }

    private static String formatCount(int count) {
        return String.format("%,d", count);
    }
}
