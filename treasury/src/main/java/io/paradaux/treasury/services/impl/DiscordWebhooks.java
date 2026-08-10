package io.paradaux.treasury.services.impl;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;

/**
 * Shared helpers for posting Discord webhook embeds — the small HTTP/JSON
 * plumbing common to the tax-cycle ({@link TaxWebhookServiceImpl}) and fine
 * ({@link FineWebhookServiceImpl}) notifications. Failures are logged and
 * swallowed: a webhook is best-effort and must never disrupt the caller.
 */
@Slf4j
final class DiscordWebhooks {

    private DiscordWebhooks() {}

    /** POSTs a Discord webhook JSON payload; logs and swallows any failure. */
    static void post(HttpClient client, String url, String payload, String context) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Treasury-Plugin/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("[{}] Discord responded with status {}", context, response.statusCode());
            }
        } catch (Exception e) {
            log.warn("[{}] Failed to post webhook: {}", context, e.getMessage());
        }
    }

    /** Serialises one embed field; blank/null values render as an em-dash. */
    static String field(String name, String value, boolean inline) {
        String v = value == null || value.isBlank() ? "—" : value;
        return "{\"name\":\"" + escapeJson(name) + "\","
                + "\"value\":\"" + escapeJson(v) + "\","
                + "\"inline\":" + inline + "}";
    }

    static String formatMoney(BigDecimal amount) {
        if (amount == null) return "$0.00";
        return new DecimalFormat("$#,##0.00").format(amount);
    }

    static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
