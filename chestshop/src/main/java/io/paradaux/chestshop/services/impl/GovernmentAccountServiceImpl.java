package io.paradaux.chestshop.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.GovernmentAccountService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.chestshop.utils.BusinessAccountUtil;
import io.paradaux.chestshop.utils.Permissions;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.model.economy.AccountType;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.entity.Player;

/**
 * Treasury GOVERNMENT accounts as shop owners (PAR-529). The account id is encoded in the same
 * synthetic UUID as a firm wallet, so payments, balances and owner classification route by id.
 */
@Singleton
@Slf4j
public class GovernmentAccountServiceImpl implements GovernmentAccountService {

    private final ChestShopConfiguration config;
    private volatile TreasuryApi treasury;

    @Inject
    public GovernmentAccountServiceImpl(ChestShopConfiguration config) {
        this.config = config;
    }

    @Override
    public void bind(TreasuryApi treasury) {
        this.treasury = treasury;
    }

    @Override
    public Account resolveGovernmentAccount(String name) {
        TreasuryApi api = treasury;
        if (api == null || name == null || name.length() < 3 || !SignService.isGovernmentAccount(name)) {
            return null;
        }
        String token = name.substring(2);
        try {
            io.paradaux.treasury.model.economy.Account account = token.startsWith("#")
                    ? api.getAccountById(Integer.parseInt(token.substring(1), 36))
                    : api.getGovernmentAccountByName(token);
            // Only a live GOVERNMENT account: G:#<id> must not reach personal or firm wallets.
            if (account == null || account.getAccountType() != AccountType.GOVERNMENT || account.isArchived()) {
                return null;
            }
            // Treasury's system faucets and sinks (starting-balances, fines…) never back a shop.
            if (config.isGovernmentShopBlocked(account.getDisplayName())) return null;
            return new Account(
                    account.getDisplayName(),
                    SignService.governmentAccountSignName(account.getAccountId(), account.getDisplayName()),
                    BusinessAccountUtil.toBusinessUuid(account.getAccountId()));
        } catch (NumberFormatException notBase36) {
            return null;
        } catch (RuntimeException e) {
            log.warn("Treasury: Could not resolve government account for " + name, e);
            return null;
        }
    }

    @Override
    public boolean canAccessGovernmentAccount(Player player, Account account) {
        if (account == null
                || account.getShortName() == null
                || account.getName() == null
                || !SignService.isGovernmentAccount(account.getShortName())
                || !BusinessAccountUtil.isBusinessUuid(account.getUuid())) {
            return false;
        }
        String node = Permissions.GOVERNMENT + account.getName().toLowerCase(Locale.ROOT);
        // An explicit false on the account's node overrides the wildcard, as for othername nodes.
        if (Permissions.hasPermissionSetFalse(player, node)) {
            return false;
        }
        return Permissions.hasNode(player, node) || Permissions.hasNode(player, Permissions.GOVERNMENT + "*");
    }
}
