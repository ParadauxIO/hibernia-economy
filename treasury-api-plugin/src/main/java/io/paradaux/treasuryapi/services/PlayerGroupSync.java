package io.paradaux.treasuryapi.services;

import java.util.UUID;

/**
 * On-demand reconciliation of one player's LuckPerms-sourced explorer group
 * memberships. Deliberately free of any LuckPerms type so services can depend on
 * it when LuckPerms (a softdepend) is absent: it is bound as an optional in
 * {@code TreasuryAPIModule} and only present when LuckPerms is.
 */
public interface PlayerGroupSync {

    void reconcilePlayer(UUID playerId);
}
