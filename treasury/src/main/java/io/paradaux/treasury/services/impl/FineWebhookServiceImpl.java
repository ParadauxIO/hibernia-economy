package io.paradaux.treasury.services.impl;

import com.google.inject.Inject;
import io.paradaux.treasury.model.config.FineWebhookConfiguration;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.GovernmentFine;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.FineWebhookService;
import io.paradaux.treasury.services.PlayerDirectoryService;
import lombok.extern.slf4j.Slf4j;

import java.net.http.HttpClient;
import java.util.UUID;

import static io.paradaux.treasury.services.impl.DiscordWebhooks.escapeJson;
import static io.paradaux.treasury.services.impl.DiscordWebhooks.field;
import static io.paradaux.treasury.services.impl.DiscordWebhooks.formatMoney;

@Slf4j
public class FineWebhookServiceImpl implements FineWebhookService {

    // Discord embed colours
    private static final int COLOR_ISSUED  = 0xED4245; // red — money taken
    private static final int COLOR_REVOKED = 0x57F287; // green — money returned

    private final FineWebhookConfiguration config;
    private final AccountService accountService;
    private final PlayerDirectoryService playerDirectory;
    private final HttpClient httpClient;

    @Inject
    public FineWebhookServiceImpl(FineWebhookConfiguration config,
                                  AccountService accountService,
                                  PlayerDirectoryService playerDirectory) {
        this.config          = config;
        this.accountService  = accountService;
        this.playerDirectory = playerDirectory;
        this.httpClient      = HttpClient.newHttpClient();
    }

    @Override
    public void sendFineIssued(GovernmentFine fine) {
        post(buildPayload(fine, true));
    }

    @Override
    public void sendFineRevoked(GovernmentFine fine) {
        post(buildPayload(fine, false));
    }

    private void post(String payload) {
        if (payload == null) return;
        DiscordWebhooks.post(httpClient, config.getUrl(), payload, "Fine Webhook");
    }

    /** Returns the JSON payload, or {@code null} when the webhook is disabled/unconfigured. */
    private String buildPayload(GovernmentFine fine, boolean issued) {
        if (!config.isEnabled()) return null;
        String url = config.getUrl();
        if (url == null || url.isBlank()) return null;

        int color   = issued ? COLOR_ISSUED : COLOR_REVOKED;
        String title = issued ? "Fine Issued" : "Fine Revoked";

        String target    = targetLabel(fine);
        String account   = accountLabel(fine.getGovAccountId());
        String amount    = formatMoney(fine.getAmount());
        String actorName = issued ? actorLabel(fine.getIssuedBy()) : actorLabel(fine.getRevokedBy());

        StringBuilder fields = new StringBuilder();
        fields.append(field("👤 Target", target, true)).append(",");
        fields.append(field(issued ? "💸 Amount" : "💸 Amount Refunded", amount, true)).append(",");
        fields.append(field(issued ? "🏛 Paid To" : "🏛 Refunded From", account, true)).append(",");
        fields.append(field(issued ? "👮 Issued By" : "↩ Revoked By", actorName, true)).append(",");
        fields.append(field("📝 Reason", fine.getReason(), false));

        long refTxn = issued
                ? fine.getTxnId()
                : (fine.getRevokeTxnId() != null ? fine.getRevokeTxnId() : 0L);
        String footer = "Fine #" + fine.getFineId()
                + " · " + (issued ? "txn #" : "reverse txn #") + refTxn
                + " · Treasury";

        return "{"
                + "\"embeds\":[{"
                + "\"title\":\"" + escapeJson(title) + "\","
                + "\"color\":" + color + ","
                + "\"fields\":[" + fields + "],"
                + "\"footer\":{\"text\":\"" + escapeJson(footer) + "\"}"
                + "}]}";
    }

    /** Who the fine is against: the player's name, or the debited account's name for firm fines. */
    private String targetLabel(GovernmentFine fine) {
        if (fine.getPlayerUuid() != null) {
            return actorLabel(fine.getPlayerUuid());
        }
        if (fine.getDebtorAccountId() != null) {
            return accountLabel(fine.getDebtorAccountId());
        }
        return "—";
    }

    private String actorLabel(UUID uuid) {
        if (uuid == null) return "—";
        return playerDirectory.resolveNameByUuid(uuid).orElseGet(uuid::toString);
    }

    private String accountLabel(Integer accountId) {
        if (accountId == null) return "—";
        Account account = accountService.getAccountById(accountId);
        return account != null && account.getDisplayName() != null
                ? account.getDisplayName()
                : "Account #" + accountId;
    }
}
