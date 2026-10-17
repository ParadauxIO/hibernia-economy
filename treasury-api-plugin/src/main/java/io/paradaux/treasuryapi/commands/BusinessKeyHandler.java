package io.paradaux.treasuryapi.commands;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.business.api.BusinessApi;
import io.paradaux.business.model.Firm;
import io.paradaux.treasuryapi.model.economy.ApiKey;
import io.paradaux.treasuryapi.model.economy.KeyType;
import io.paradaux.treasuryapi.services.ApiKeyService;
import org.bukkit.entity.Player;

import java.util.List;

@Singleton
public class BusinessKeyHandler {

    private final ApiKeyService apiKeyService;
    private final BusinessApi businessApi;
    private final Message message;

    @Inject
    public BusinessKeyHandler(ApiKeyService apiKeyService,
                              BusinessApi businessApi,
                              Message message) {
        this.apiKeyService = apiKeyService;
        this.businessApi = businessApi;
        this.message = message;
    }

    public void doIssue(Player sender, String firmName) {
        Firm firm = businessApi.firms().getFirm(firmName);
        if (firm == null) {
            message.send(sender, "treasuryapi.business.issue.firm-not-found");
            return;
        }

        if (!businessApi.firms().isProprietor(firm.getFirmId(), sender.getUniqueId())) {
            message.send(sender, "treasuryapi.business.issue.no-access");
            return;
        }

        ApiKey key = apiKeyService.issueBusinessKey(firm.getFirmId(), sender.getUniqueId());
        message.send(sender, "treasuryapi.business.issue.success",
                "keyId", String.valueOf(key.getKeyId()),
                "firmId", String.valueOf(key.getFirmId()),
                "firmName", firm.getDisplayName(),
                "token", key.getToken());
    }

    public void doList(Player sender) {
        List<ApiKey> keys = apiKeyService.listKeys(sender.getUniqueId(), KeyType.BUSINESS);
        if (keys.isEmpty()) {
            message.send(sender, "treasuryapi.business.list.empty");
            return;
        }
        message.send(sender, "treasuryapi.business.list.header",
                "count", String.valueOf(keys.size()));
        for (ApiKey key : keys) {
            message.send(sender, "treasuryapi.business.list.entry",
                    "keyId", String.valueOf(key.getKeyId()),
                    "firmId", String.valueOf(key.getFirmId()),
                    "status", ApiKeyView.status(key, message),
                    "expiry", ApiKeyView.expiry(key, message));
        }
    }

    public void doListAccess(Player sender) {
        List<ApiKey> keys = apiKeyService.listBusinessKeysAccessibleByEmployee(sender.getUniqueId());
        if (keys.isEmpty()) {
            message.send(sender, "treasuryapi.business.list.access.empty");
            return;
        }
        message.send(sender, "treasuryapi.business.list.access.header",
                "count", String.valueOf(keys.size()));
        for (ApiKey key : keys) {
            message.send(sender, "treasuryapi.business.list.access.entry",
                    "keyId", String.valueOf(key.getKeyId()),
                    "firmId", String.valueOf(key.getFirmId()),
                    "status", ApiKeyView.status(key, message),
                    "expiry", ApiKeyView.expiry(key, message));
        }
    }

    public void doReissue(Player sender, int keyId) {
        ApiKey key = apiKeyService.getKey(keyId);
        if (key == null || key.getKeyType() != KeyType.BUSINESS) {
            message.send(sender, "treasuryapi.business.reissue.not-found");
            return;
        }
        if (!canManage(key, sender)) {
            message.send(sender, "treasuryapi.business.reissue.no-access");
            return;
        }
        // Revocation is terminal (ADT-110); the service rejects reissue of a
        // revoked key. Pre-check so the proprietor gets a clear message rather
        // than an error from the thrown exception.
        if (key.isRevoked()) {
            message.send(sender, "treasuryapi.business.reissue.revoked");
            return;
        }
        // The service re-checks proprietorship as the authoritative boundary
        // (pa/0005); these handler branches are the fast path for a clear message.
        ApiKey updated = apiKeyService.reissueKey(keyId, sender.getUniqueId());
        message.send(sender, "treasuryapi.business.reissue.success",
                "keyId", String.valueOf(updated.getKeyId()),
                "token", updated.getToken());
    }

    public void doRevoke(Player sender, int keyId) {
        ApiKey key = apiKeyService.getKey(keyId);
        if (key == null || key.getKeyType() != KeyType.BUSINESS) {
            message.send(sender, "treasuryapi.business.revoke.not-found");
            return;
        }
        if (!canManage(key, sender)) {
            message.send(sender, "treasuryapi.business.revoke.no-access");
            return;
        }
        apiKeyService.revokeKey(keyId, sender.getUniqueId());
        message.send(sender, "treasuryapi.business.revoke.success",
                "keyId", String.valueOf(keyId));
    }

    // ADT-111: a BUSINESS key is firm-scoped, so reissue/revoke must be gated on
    // CURRENT proprietorship of the key's firm — not on the individual who happened
    // to issue it. Otherwise, once the issuing proprietor leaves or transfers the
    // firm, no current proprietor can rotate or revoke the firm's own credential.
    private boolean canManage(ApiKey key, Player sender) {
        return key.getFirmId() != null
                && businessApi.firms().isProprietor(key.getFirmId(), sender.getUniqueId());
    }
}
