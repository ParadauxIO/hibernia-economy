package io.paradaux.treasuryrestapi.util;

import io.paradaux.treasuryrestapi.model.DueDelivery;

import java.math.BigDecimal;
import java.net.URI;
import java.text.DecimalFormat;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Recognises Discord webhook targets and renders a transaction delivery as a
 * Discord <a href="https://discord.com/developers/docs/resources/webhook#execute-webhook">
 * embed</a> payload instead of the canonical {@code WebhookEvent} JSON.
 *
 * <p>Discord ignores arbitrary bodies and unknown headers, so a player who
 * registers a channel webhook would otherwise get an unreadable raw event. When
 * we detect a Discord URL we POST the {@code {username, embeds:[…]}} shape it
 * understands — no signature (Discord can't verify it and the body isn't our
 * documented event). The SSRF guard still applies; Discord resolves to public
 * Cloudflare addresses, so it passes.
 */
public final class DiscordWebhook {

    private DiscordWebhook() {}

    private static final Set<String> HOSTS = Set.of(
            "discord.com", "discordapp.com", "canary.discord.com", "ptb.discord.com");
    // /api/webhooks/<id>/<token>, optionally version-pinned (/api/v10/webhooks/…).
    private static final Pattern PATH = Pattern.compile("^/api(/v\\d+)?/webhooks/\\d+/.+");

    private static final int COLOR_CREDIT = 0x2ECC71; // green
    private static final int COLOR_DEBIT = 0xE74C3C;  // red

    /** True if {@code uri} is a Discord channel/webhook execute URL. */
    public static boolean isDiscordWebhook(URI uri) {
        if (uri == null) return false;
        String host = uri.getHost();
        String path = uri.getPath();
        if (host == null || path == null) return false;
        return HOSTS.contains(host.toLowerCase()) && PATH.matcher(path).matches();
    }

    /** Builds the Discord embed payload for a due delivery. */
    public static Payload toPayload(DueDelivery d) {
        BigDecimal amount = d.getAmount() != null ? d.getAmount() : BigDecimal.ZERO;
        boolean credit = amount.signum() >= 0;

        List<Field> fields = new ArrayList<>();
        fields.add(new Field("Amount", money(amount), true));
        fields.add(new Field("Account", "#" + d.getAccountId(), true));
        fields.add(new Field("Transaction", "#" + d.getTxnId(), true));
        if (notBlank(d.getPluginSystem())) {
            fields.add(new Field("System", trim(d.getPluginSystem(), 1024), true));
        }
        if (notBlank(d.getMemo()) && !d.getMemo().equals(d.getMessage())) {
            fields.add(new Field("Memo", trim(d.getMemo(), 1024), false));
        }
        if (d.getInitiatorUuidBin() != null) {
            fields.add(new Field("Initiator", d.getInitiatorUuidBin().toString(), false));
        }

        String timestamp = d.getSettlementTime() != null
                ? d.getSettlementTime().toInstant(ZoneOffset.UTC).toString() : null;
        String description = notBlank(d.getMessage()) ? trim(d.getMessage(), 4096) : null;

        Embed embed = new Embed(
                credit ? "💰 Credit" : "💸 Debit",
                description,
                credit ? COLOR_CREDIT : COLOR_DEBIT,
                fields,
                timestamp,
                new Footer("Treasury • delivery " + d.getDeliveryId()));
        return new Payload("Treasury", List.of(embed));
    }

    /** Signed, grouped money string: {@code +$1,234.56} / {@code -$500.00}. */
    private static String money(BigDecimal amount) {
        // DecimalFormat is not thread-safe; build a fresh instance per call rather
        // than sharing a static one across webhook-delivery threads.
        String sign = amount.signum() < 0 ? "-" : "+";
        return sign + "$" + new DecimalFormat("#,##0.00").format(amount.abs());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    // Discord payload shape. Record component names match Discord's JSON keys, so
    // Jackson serialises them directly. Null components are omitted upstream by
    // the ObjectMapper's default (non-null) inclusion where configured; Discord
    // tolerates explicit nulls regardless.
    public record Payload(String username, List<Embed> embeds) {}
    public record Embed(String title, String description, int color,
                        List<Field> fields, String timestamp, Footer footer) {}
    public record Field(String name, String value, boolean inline) {}
    public record Footer(String text) {}
}
