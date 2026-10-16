package io.paradaux.treasury.services.impl;

import com.sun.net.httpserver.HttpServer;
import io.paradaux.treasury.model.config.DiscordWebhookConfiguration;
import io.paradaux.treasury.model.tax.TaxCycleReport;
import io.paradaux.treasury.model.tax.TaxCycleType;
import io.paradaux.treasury.testsupport.TestConfigs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class TaxWebhookServiceImplTest {

    private HttpServer server;
    private final AtomicReference<String> lastPayload = new AtomicReference<>();
    private final AtomicInteger callCount = new AtomicInteger();
    private volatile int responseStatus = 200;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            callCount.incrementAndGet();
            lastPayload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resp = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void sendCycleReport_disabled_skipsHttpCall() {
        DiscordWebhookConfiguration cfg = TestConfigs.discordWebhook(false, hookUrl());
        TaxWebhookServiceImpl svc = new TaxWebhookServiceImpl(cfg);

        svc.sendCycleReport(scheduledReport());

        assertThat(callCount).hasValue(0);
    }

    @Test
    void sendCycleReport_enabledButBlankUrl_skipsHttpCall() {
        DiscordWebhookConfiguration cfg = TestConfigs.discordWebhook(true, "");
        TaxWebhookServiceImpl svc = new TaxWebhookServiceImpl(cfg);

        svc.sendCycleReport(scheduledReport());

        assertThat(callCount).hasValue(0);
    }

    @Test
    void sendCycleReport_scheduled_postsGreenEmbedWithCounts() {
        DiscordWebhookConfiguration cfg = TestConfigs.discordWebhook(true, hookUrl());
        TaxWebhookServiceImpl svc = new TaxWebhookServiceImpl(cfg);

        svc.sendCycleReport(scheduledReport());

        assertThat(callCount).hasValue(1);
        String body = lastPayload.get();
        assertThat(body).contains("\"color\":5763719");      // green for scheduled
        assertThat(body).contains("WEEKLY Tax Cycle Complete");
        assertThat(body).contains("Total Collected");
        assertThat(body).contains("$300.00");
        assertThat(body).contains("DCGovernment");
        assertThat(body).contains("balance-tax");
        assertThat(body).contains("3 collected");
        assertThat(body).contains("1 skipped");
        assertThat(body).contains("0 failed");
    }

    @Test
    void sendCycleReport_manual_postsOrangeEmbedWithTriggeredBy() {
        DiscordWebhookConfiguration cfg = TestConfigs.discordWebhook(true, hookUrl());
        TaxWebhookServiceImpl svc = new TaxWebhookServiceImpl(cfg);

        svc.sendCycleReport(manualReport("rian"));

        String body = lastPayload.get();
        assertThat(body).contains("\"color\":15105570");     // 0xE67E22 — orange for manual
        assertThat(body).contains("[MANUAL]");
        assertThat(body).contains("rian");
    }

    @Test
    void sendCycleReport_escapesQuotesAndBackslashesInTriggeredBy() {
        DiscordWebhookConfiguration cfg = TestConfigs.discordWebhook(true, hookUrl());
        TaxWebhookServiceImpl svc = new TaxWebhookServiceImpl(cfg);

        svc.sendCycleReport(manualReport("evil\"name\\with\nnewline"));

        String body = lastPayload.get();
        assertThat(body).contains("evil\\\"name\\\\with\\nnewline");
    }

    @Test
    void sendCycleReport_emptyMaps_renderAsDash() {
        DiscordWebhookConfiguration cfg = TestConfigs.discordWebhook(true, hookUrl());
        TaxWebhookServiceImpl svc = new TaxWebhookServiceImpl(cfg);

        TaxCycleReport empty = new TaxCycleReport(
                TaxCycleType.MONTHLY, Instant.parse("2026-03-01T03:00:00Z"),
                false, null, BigDecimal.ZERO, Map.of(), Map.of(), 0, 0, 0);
        svc.sendCycleReport(empty);

        // Both breakdown fields should render as "—" (the placeholder)
        String body = lastPayload.get();
        long dashes = body.chars().filter(c -> c == '—').count();
        assertThat(dashes).isGreaterThanOrEqualTo(2);
    }

    @Test
    void sendCycleReport_serverError_doesNotThrow() {
        responseStatus = 500;
        DiscordWebhookConfiguration cfg = TestConfigs.discordWebhook(true, hookUrl());
        TaxWebhookServiceImpl svc = new TaxWebhookServiceImpl(cfg);

        // Should not propagate
        svc.sendCycleReport(scheduledReport());

        assertThat(callCount).hasValue(1);
    }

    @Test
    void sendCycleReport_unreachableHost_swallowsException() {
        // No server listening — HttpClient throws IOException, the service must swallow it.
        DiscordWebhookConfiguration cfg = TestConfigs.discordWebhook(true,
                "http://127.0.0.1:1/never-listens"); // port 1 reliably refused
        TaxWebhookServiceImpl svc = new TaxWebhookServiceImpl(cfg);

        assertThatCode(() -> svc.sendCycleReport(scheduledReport())).doesNotThrowAnyException();
    }

    // ---- Fixtures ----

    private String hookUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private TaxCycleReport scheduledReport() {
        Map<String, BigDecimal> byAccount = new LinkedHashMap<>();
        byAccount.put("DCGovernment", new BigDecimal("250.00"));
        byAccount.put("GovernmentFines", new BigDecimal("50.00"));
        Map<String, BigDecimal> byType = new LinkedHashMap<>();
        byType.put("balance-tax", new BigDecimal("250.00"));
        byType.put("fine-tax",    new BigDecimal("50.00"));
        return new TaxCycleReport(
                TaxCycleType.WEEKLY, Instant.parse("2026-02-01T03:00:00Z"),
                false, null,
                new BigDecimal("300.00"), byAccount, byType,
                3, 1, 0);
    }

    private TaxCycleReport manualReport(String triggeredBy) {
        return new TaxCycleReport(
                TaxCycleType.DAILY, Instant.parse("2026-02-02T12:00:00Z"),
                true, triggeredBy,
                new BigDecimal("10.00"),
                Map.of("DCGovernment", new BigDecimal("10.00")),
                Map.of("manual-tax",   new BigDecimal("10.00")),
                1, 0, 0);
    }
}
