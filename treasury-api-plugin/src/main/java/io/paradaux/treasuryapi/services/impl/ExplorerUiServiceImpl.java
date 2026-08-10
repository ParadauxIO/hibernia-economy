package io.paradaux.treasuryapi.services.impl;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import io.paradaux.hibernia.framework.exceptions.BadCommandException;
import io.paradaux.hibernia.framework.exceptions.ConflictException;
import io.paradaux.hibernia.framework.exceptions.NotFoundException;
import io.paradaux.treasuryapi.TreasuryAPI;
import io.paradaux.treasuryapi.mappers.ExplorerUiMapper;
import io.paradaux.treasuryapi.services.ExplorerUiService;
import io.paradaux.treasuryapi.services.KeycloakAdminClient;
import io.paradaux.treasuryapi.tasks.GroupReconciliationTask;
import org.bukkit.Bukkit;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Owns the explorer-UI access persistence + Keycloak orchestration. The command
 * handler ({@code UiAccessHandler}) normalises input and renders messages; this
 * service performs the writes and throws the framework's semantic exceptions on
 * invalid state (treasury-api-plugin/structure/0001, plugin-architecture/0001).
 */
@Singleton
public class ExplorerUiServiceImpl implements ExplorerUiService {

    private static final Set<String> VALID_ROLES = Set.of("admin", "government");

    private final ExplorerUiMapper mapper;
    private final KeycloakAdminClient keycloak;
    private final TreasuryAPI plugin;
    // A Provider (not the concrete task, and NOT the Guice Injector — the former
    // service-locator smell, plugin-architecture/0002) breaks the eager dependency:
    // GroupReconciliationTask is only bound when LuckPerms is present, so it is
    // resolved lazily and only after the LuckPerms guard passes.
    private final Provider<GroupReconciliationTask> reconciliationTask;

    @Inject
    public ExplorerUiServiceImpl(ExplorerUiMapper mapper,
                                 KeycloakAdminClient keycloak,
                                 TreasuryAPI plugin,
                                 Provider<GroupReconciliationTask> reconciliationTask) {
        this.mapper = mapper;
        this.keycloak = keycloak;
        this.plugin = plugin;
        this.reconciliationTask = reconciliationTask;
    }

    @Override
    public String grantRole(UUID targetUuid, String role, UUID grantedBy) {
        String r = role.toLowerCase(Locale.ROOT);
        if (!VALID_ROLES.contains(r)) {
            throw new BadCommandException("treasuryapi.ui.user.invalid-role", "role", role);
        }
        mapper.addRole(targetUuid, r, grantedBy);
        // Audit trail: privileged role grants are security-relevant.
        plugin.getLogger().info("UI role grant: '" + r + "' -> " + targetUuid
                + " by " + (grantedBy != null ? grantedBy.toString() : "CONSOLE"));
        return r;
    }

    @Override
    public boolean revokeRole(UUID targetUuid, String role) {
        String r = role.toLowerCase(Locale.ROOT);
        return mapper.removeRole(targetUuid, r) > 0;
    }

    @Override
    public List<String> listRoles(UUID targetUuid) {
        return mapper.listRoles(targetUuid);
    }

    @Override
    public LinkOutcome redeemLinkCode(String code, UUID playerUuid, String playerName) {
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        // Read the sub first so it's available for upsertIdentity, but the read is
        // NOT the single-use guard — the conditional DELETE below is. Both /link
        // routes are @Async, so two concurrent redemptions can both pass this
        // SELECT; the authoritative claim is claimLinkCode's affected-row count.
        String sub = mapper.findValidLinkSub(normalized);
        if (sub == null) {
            throw new NotFoundException("treasuryapi.ui.link.invalid");
        }

        // Atomically claim the code (delete-if-unexpired). Exactly one concurrent
        // caller gets count 1 and wins; every other caller gets 0 (already redeemed
        // or expired between the SELECT and here) and is cleanly rejected, so a
        // single-use code can never redeem twice or clobber the winner's identity.
        if (mapper.claimLinkCode(normalized) != 1) {
            throw new NotFoundException("treasuryapi.ui.link.invalid");
        }

        mapper.upsertIdentity(sub, playerUuid, playerName, "in-game:" + playerName);

        if (keycloak.isEnabled()) {
            try {
                keycloak.setMinecraftAttributes(sub, playerUuid, playerName);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Keycloak attribute update failed for sub=" + sub, e);
                // Linked locally; the next login resolves via the link table.
                return LinkOutcome.LINKED_KEYCLOAK_PENDING;
            }
        }
        return LinkOutcome.LINKED;
    }

    @Override
    public void selfSync(UUID playerUuid) {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            throw new ConflictException("treasuryapi.ui.sync.unavailable");
        }
        try {
            reconciliationTask.get().reconcilePlayer(playerUuid);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Self-sync failed for " + playerUuid, e);
            throw new ConflictException("treasuryapi.ui.sync.failed", e);
        }
    }
}
