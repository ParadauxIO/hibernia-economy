package io.paradaux.treasuryapi.commands;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasury.api.FeatureFlagApi;
import io.paradaux.treasuryapi.model.economy.ApiKey;
import io.paradaux.treasuryapi.model.economy.KeyType;
import io.paradaux.treasuryapi.services.ApiKeyService;
import java.util.List;
import org.bukkit.entity.Player;

@Singleton
public class GovernmentKeyHandler {

    private final ApiKeyService apiKeyService;
    private final Message message;
    private final FeatureFlagApi flags;

    @Inject
    public GovernmentKeyHandler(ApiKeyService apiKeyService, Message message, FeatureFlagApi flags) {
        this.apiKeyService = apiKeyService;
        this.message = message;
        this.flags = flags;
    }

    public void doIssue(Player sender, int accountId) {
        if (!requireEnabled(sender)) return;
        ApiKey key = apiKeyService.issueGovernmentKey(accountId, sender.getUniqueId());
        message.send(
                sender,
                "treasuryapi.government.issue.success",
                "keyId",
                String.valueOf(key.getKeyId()),
                "accountId",
                String.valueOf(key.getAccountId()),
                "token",
                key.getToken());
    }

    public void doList(Player sender) {
        List<ApiKey> keys = apiKeyService.listKeys(sender.getUniqueId(), KeyType.GOVERNMENT);
        if (keys.isEmpty()) {
            message.send(sender, "treasuryapi.government.list.empty");
            return;
        }
        message.send(sender, "treasuryapi.government.list.header", "count", String.valueOf(keys.size()));
        for (ApiKey key : keys) {
            message.send(
                    sender,
                    "treasuryapi.government.list.entry",
                    "keyId",
                    String.valueOf(key.getKeyId()),
                    "accountId",
                    String.valueOf(key.getAccountId()),
                    "status",
                    ApiKeyView.status(key, message),
                    "expiry",
                    ApiKeyView.expiry(key, message));
        }
    }

    public void doReissue(Player sender, int keyId) {
        if (!requireEnabled(sender)) return;
        ApiKey key = apiKeyService.getKey(keyId);
        if (key == null || key.getKeyType() != KeyType.GOVERNMENT) {
            message.send(sender, "treasuryapi.government.reissue.not-found");
            return;
        }
        if (!key.getOwnerUuid().equals(sender.getUniqueId())) {
            message.send(sender, "treasuryapi.government.reissue.no-access");
            return;
        }
        // Revocation is terminal (ADT-110); the service rejects reissue of a
        // revoked key. Pre-check so the owner gets a clear message rather than an
        // error from the thrown exception.
        if (key.isRevoked()) {
            message.send(sender, "treasuryapi.government.reissue.revoked");
            return;
        }
        // The service re-checks ownership as the authoritative boundary (pa/0005);
        // these handler branches are the fast path for a clear, key-specific message.
        ApiKey updated = apiKeyService.reissueKey(keyId, sender.getUniqueId());
        message.send(
                sender,
                "treasuryapi.government.reissue.success",
                "keyId",
                String.valueOf(updated.getKeyId()),
                "token",
                updated.getToken());
    }

    public void doRevoke(Player sender, int keyId) {
        ApiKey key = apiKeyService.getKey(keyId);
        if (key == null || key.getKeyType() != KeyType.GOVERNMENT) {
            message.send(sender, "treasuryapi.government.revoke.not-found");
            return;
        }
        if (!key.getOwnerUuid().equals(sender.getUniqueId())) {
            message.send(sender, "treasuryapi.government.revoke.no-access");
            return;
        }
        apiKeyService.revokeKey(keyId, sender.getUniqueId());
        message.send(sender, "treasuryapi.government.revoke.success", "keyId", String.valueOf(keyId));
    }

    private boolean requireEnabled(Player sender) {
        if (flags.isEnabled("treasury.government_api_keys")) return true;
        message.send(sender, "treasuryapi.government.disabled");
        return false;
    }
}
