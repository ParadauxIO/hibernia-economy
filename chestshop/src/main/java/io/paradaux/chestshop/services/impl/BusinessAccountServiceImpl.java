package io.paradaux.chestshop.services.impl;
import lombok.extern.slf4j.Slf4j;

import io.paradaux.chestshop.services.EconomyService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.chestshop.services.BusinessAccountService;
import com.google.inject.Singleton;
import io.paradaux.business.api.BusinessApi;
import io.paradaux.business.model.RolePermission;
import io.paradaux.chestshop.ChestShop;
import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.utils.BusinessAccountUtil;
import io.paradaux.treasury.api.TreasuryApi;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

/**
 * Resolves and access-checks Business-plugin firm accounts against the Treasury/Business APIs
 * (the {@code B:<base36 id>} owner sign form, and legacy firm-name form). Split out of
 * {@code EconomyService} (PAR-282): this is read-only account resolution, not money movement,
 * and hosting it in its own leaf service — holding just the API handles, depending on no other
 * ChestShop service — dissolves the former {@code AccountService ↔ EconomyService} construction
 * cycle (AccountService needed EconomyService <em>only</em> for these two methods, and now
 * depends on this leaf directly). The synthetic-UUID codec is the shared
 * {@link BusinessAccountUtil}.
 */
@Singleton
@Slf4j
public class BusinessAccountServiceImpl implements BusinessAccountService {

    private volatile TreasuryApi treasury;
    @Nullable private volatile BusinessApi businessApi;

    /** Wire the resolved Treasury handle + optional Business API in once available (enable time). */
    @Override
    public void bind(TreasuryApi treasury, @Nullable BusinessApi businessApi) {
        this.treasury = treasury;
        this.businessApi = businessApi;
    }

    @Override
    public Account resolveBusinessAccount(String name) {
        if (name == null || name.length() < 3 || !name.regionMatches(true, 0, "B:", 0, 2)) {
            return null;
        }
        try {
            String token = name.substring(2);
            int accountId = -1;
            io.paradaux.treasury.model.economy.Account treasuryAccount = null;

            // Native form: the suffix is a base-36 Treasury account id (e.g. B:1A).
            try {
                accountId = Integer.parseInt(token, 36);
                treasuryAccount = treasury.getAccountById(accountId);
            } catch (NumberFormatException notBase36) {
                // e.g. a legacy firm name containing spaces — fall through to the name lookup.
            }

            // Legacy form: the suffix is an old PlayerBusinesses firm *name* (e.g. b:My Shop).
            if (treasuryAccount == null && businessApi != null) {
                io.paradaux.business.model.Firm firm = businessApi.firms().getFirm(token);
                if (firm != null && firm.getDefaultAccountId() != null) {
                    accountId = firm.getDefaultAccountId();
                    treasuryAccount = treasury.getAccountById(accountId);
                }
            }

            if (treasuryAccount != null) {
                String displayName = treasuryAccount.getDisplayName();
                String shortName = SignService.businessAccountSignName(accountId);
                return new Account(displayName, shortName, BusinessAccountUtil.toBusinessUuid(accountId));
            }
        } catch (Exception e) {
            log.warn("Treasury: Could not resolve business account for " + name, e);
        }
        return null;
    }

    @Override
    public boolean canAccessBusinessAccount(Player player, Account account) {
        if (account == null || account.getShortName() == null) {
            return false;
        }
        if (!account.getShortName().toUpperCase(Locale.ROOT).startsWith("B:")) {
            return false;
        }
        UUID uuid = account.getUuid();
        if (!BusinessAccountUtil.isBusinessUuid(uuid)) {
            return false;
        }
        try {
            int accountId = (int) uuid.getLeastSignificantBits();
            UUID playerUuid = player.getUniqueId();
            if (businessApi != null) {
                if (businessApi.staff().hasPermissionForAccount(accountId, playerUuid, RolePermission.CHESTSHOP)) {
                    return true;
                }
                // PAR-29: no live firm owns this account (disbanded) — leave it accessible.
                return businessApi.firms().getFirmByAccountId(accountId) == null;
            }
            // Business plugin absent: fall back to Treasury account membership.
            return treasury.isAccountMember(playerUuid, accountId)
                    || treasury.isOwnerForAccountId(playerUuid, accountId);
        } catch (Exception e) {
            log.warn("Treasury: Could not check access for " + player.getName() + " on account " + account.getShortName(), e);
            return false;
        }
    }
}
