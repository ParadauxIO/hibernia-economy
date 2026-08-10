package io.paradaux.treasuryapi.services;

import java.util.List;
import java.util.UUID;

/**
 * Owns the explorer-UI access domain: role grants ({@code explorer_role}), the
 * identity link + Keycloak attribute push ({@code explorer_link_code} /
 * {@code explorer_identity}), and on-demand LuckPerms self-sync. Extracted from
 * {@code UiAccessHandler} (treasury-api-plugin/structure/0001,
 * plugin-architecture/0001) so the command layer stays thin and the persistence +
 * Keycloak orchestration lives behind one service that validates its inputs and
 * throws the framework's semantic exceptions on invalid state.
 */
public interface ExplorerUiService {

    /**
     * Grants {@code role} to {@code targetUuid}, attributed to {@code grantedBy}
     * (null for console). Throws {@code BadCommandException} if {@code role} is not a
     * valid explorer role. Returns the normalised (lower-cased) role that was written.
     */
    String grantRole(UUID targetUuid, String role, UUID grantedBy);

    /**
     * Revokes {@code role} from {@code targetUuid}. Returns {@code true} if a row was
     * removed, {@code false} if the player did not hold the role.
     */
    boolean revokeRole(UUID targetUuid, String role);

    /** Lists a player's explorer roles, ordered. */
    List<String> listRoles(UUID targetUuid);

    /**
     * Redeems a link code, writing the identity and (if Keycloak is enabled) pushing
     * the minecraft attributes. Throws {@code NotFoundException} if the code is
     * unknown/expired or was already claimed by a concurrent redemption. Returns
     * {@link LinkOutcome#LINKED} on full success or {@link LinkOutcome#LINKED_KEYCLOAK_PENDING}
     * when the local link succeeded but the Keycloak push failed (retried on next login).
     */
    LinkOutcome redeemLinkCode(String code, UUID playerUuid, String playerName);

    /**
     * Reconciles the player's LuckPerms-sourced explorer memberships now. Throws
     * {@code ConflictException} if role syncing is unavailable (LuckPerms absent).
     */
    void selfSync(UUID playerUuid);

    /** Result of a link-code redemption. */
    enum LinkOutcome {
        LINKED,
        LINKED_KEYCLOAK_PENDING
    }
}
