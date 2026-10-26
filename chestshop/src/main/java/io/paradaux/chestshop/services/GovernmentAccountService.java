package io.paradaux.chestshop.services;

import io.paradaux.chestshop.model.Account;
import io.paradaux.treasury.api.TreasuryApi;
import org.bukkit.entity.Player;

/**
 * Resolves and access-checks Treasury GOVERNMENT accounts used as shop owners: the
 * {@code G:<display name>} sign form, or {@code G:#<base36 id>} for names too long for a sign
 * line (PAR-529). A leaf service like {@link BusinessAccountService}: it holds only the Treasury
 * handle, and money routing reuses the account-id UUID codec in
 * {@link io.paradaux.chestshop.utils.BusinessAccountUtil}.
 */
public interface GovernmentAccountService {

    /** Wire the resolved Treasury handle in once available (enable time). */
    void bind(TreasuryApi treasury);

    /** Resolve a {@code G:} owner token to a live government account, or {@code null}. */
    Account resolveGovernmentAccount(String name);

    /**
     * Whether {@code player} may use a government account as a shop owner: the per-account
     * {@code ChestShop.government.<name>} node, or {@code ChestShop.government.*}.
     */
    boolean canAccessGovernmentAccount(Player player, Account account);
}
