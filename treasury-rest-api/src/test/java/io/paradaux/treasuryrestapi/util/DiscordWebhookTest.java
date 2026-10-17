package io.paradaux.treasuryrestapi.util;

import io.paradaux.treasuryrestapi.model.DueDelivery;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordWebhookTest {

    @Test
    void detectsDiscordHostsAndPaths() {
        assertThat(DiscordWebhook.isDiscordWebhook(URI.create(
                "https://discord.com/api/webhooks/123/abcDEF"))).isTrue();
        assertThat(DiscordWebhook.isDiscordWebhook(URI.create(
                "https://discord.com/api/v10/webhooks/123/abcDEF"))).isTrue();
        assertThat(DiscordWebhook.isDiscordWebhook(URI.create(
                "https://discordapp.com/api/webhooks/123/tok"))).isTrue();
        assertThat(DiscordWebhook.isDiscordWebhook(URI.create(
                "https://ptb.discord.com/api/webhooks/123/tok"))).isTrue();
        assertThat(DiscordWebhook.isDiscordWebhook(URI.create(
                "https://canary.discord.com/api/webhooks/123/tok"))).isTrue();
    }

    @Test
    void rejectsNonDiscordAndLookalikes() {
        assertThat(DiscordWebhook.isDiscordWebhook(URI.create(
                "https://example.com/api/webhooks/123/tok"))).isFalse();
        // not the webhook path
        assertThat(DiscordWebhook.isDiscordWebhook(URI.create(
                "https://discord.com/api/channels/123"))).isFalse();
        // missing token segment
        assertThat(DiscordWebhook.isDiscordWebhook(URI.create(
                "https://discord.com/api/webhooks/123"))).isFalse();
        // host-spoof subdomain trick
        assertThat(DiscordWebhook.isDiscordWebhook(URI.create(
                "https://discord.com.evil.example/api/webhooks/1/t"))).isFalse();
    }

    @Test
    void buildsCreditEmbed() {
        DueDelivery d = delivery(new BigDecimal("1234.50"), "Payment from Alice to Bob", "wages", "TREASURY");
        DiscordWebhook.Payload p = DiscordWebhook.toPayload(d);

        assertThat(p.username()).isEqualTo("Treasury");
        assertThat(p.embeds()).hasSize(1);
        DiscordWebhook.Embed e = p.embeds().get(0);
        assertThat(e.title()).isEqualTo("💰 Credit");
        assertThat(e.color()).isEqualTo(0x2ECC71);
        assertThat(e.description()).isEqualTo("Payment from Alice to Bob");
        assertThat(e.footer().text()).contains("delivery 42");
        assertThat(e.fields()).anySatisfy(f -> {
            assertThat(f.name()).isEqualTo("Amount");
            assertThat(f.value()).isEqualTo("+$1,234.50");
        });
        // memo differs from message → its own field
        assertThat(e.fields()).anyMatch(f -> f.name().equals("Memo") && f.value().equals("wages"));
    }

    @Test
    void buildsDebitEmbedWithNegativeSign() {
        DueDelivery d = delivery(new BigDecimal("-500.00"), null, null, null);
        DiscordWebhook.Embed e = DiscordWebhook.toPayload(d).embeds().get(0);
        assertThat(e.title()).isEqualTo("💸 Debit");
        assertThat(e.color()).isEqualTo(0xE74C3C);
        assertThat(e.fields()).anyMatch(f -> f.name().equals("Amount") && f.value().equals("-$500.00"));
        // no memo/system/description when absent
        assertThat(e.description()).isNull();
        assertThat(e.fields()).noneMatch(f -> f.name().equals("System") || f.name().equals("Memo"));
    }

    @Test
    void showsCounterpartyWhenPresent() {
        DueDelivery d = delivery(new BigDecimal("-25.00"), null, null, null);
        d.setCounterpartyAccountId(77L);
        d.setCounterpartyAccountType("BUSINESS");
        d.setCounterpartyDisplayName("Acme Corporate Account");
        DiscordWebhook.Embed e = DiscordWebhook.toPayload(d).embeds().get(0);
        assertThat(e.fields()).anyMatch(f -> f.name().equals("Counterparty")
                && f.value().equals("#77 Acme Corporate Account (BUSINESS)"));
    }

    @Test
    void omitsCounterpartyWhenAbsent() {
        DueDelivery d = delivery(new BigDecimal("5.00"), null, null, null);
        DiscordWebhook.Embed e = DiscordWebhook.toPayload(d).embeds().get(0);
        assertThat(e.fields()).noneMatch(f -> f.name().equals("Counterparty"));
    }

    private static DueDelivery delivery(BigDecimal amount, String message, String memo, String system) {
        DueDelivery d = new DueDelivery();
        d.setDeliveryId(42);
        d.setSubscriptionId(7);
        d.setTxnId(1001);
        d.setAccountId(55);
        d.setAmount(amount);
        d.setMessage(message);
        d.setMemo(memo);
        d.setPluginSystem(system);
        d.setSettlementTime(LocalDateTime.of(2026, 6, 14, 12, 0, 0));
        d.setInitiatorUuidBin(UUID.fromString("11111111-2222-3333-4444-555555555555"));
        return d;
    }
}
