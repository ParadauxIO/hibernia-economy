package io.paradaux.chestshop.services;

import io.paradaux.business.api.BusinessApi;
import io.paradaux.chestshop.model.Account;
import io.paradaux.treasury.api.TreasuryApi;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves and access-checks Business-plugin firm accounts against the Treasury/Business APIs
 * (the {@code B:<base36 id>} owner sign form, and legacy firm-name form). Split out of
 * {@code EconomyService} (PAR-282): this is read-only account resolution, not money movement,
 * and hosting it in its own leaf service — holding just the API handles, depending on no other
 * ChestShop service — dissolved the former {@code AccountService ↔ EconomyService} construction
 * cycle. The synthetic-UUID codec is the shared {@link io.paradaux.chestshop.utils.BusinessAccountUtil}.
 */
public interface BusinessAccountService {

    /** Wire the resolved Treasury handle + optional Business API in once available (enable time). */
    void bind(TreasuryApi treasury, @Nullable BusinessApi businessApi);

    /**
     * Resolve a business-account sign name ({@code B:<base36 id>}, or a legacy
     * PlayerBusinesses firm name) to a ChestShop {@link Account}, or {@code null}.
     */
    Account resolveBusinessAccount(String name);

    /**
     * Whether {@code player} may use a business account as a shop owner: a firm CHESTSHOP
     * permission (Business API), else Treasury account membership/ownership.
     */
    boolean canAccessBusinessAccount(Player player, Account account);
}
