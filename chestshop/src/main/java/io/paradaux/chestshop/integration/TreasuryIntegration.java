package io.paradaux.chestshop.integration;
import lombok.extern.slf4j.Slf4j;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.business.api.BusinessApi;
import io.paradaux.chestshop.ChestShop;
import io.paradaux.chestshop.services.BusinessAccountService;
import io.paradaux.chestshop.services.EconomyService;
import io.paradaux.chestshop.utils.BusinessAccountUtil;
import io.paradaux.treasury.api.TaxApi;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountType;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.List;

/**
 * Treasury integration — ChestShop's economy boundary, and its one {@linkplain #required()
 * required} integration: without the ledger there is nowhere to move money, so a missing
 * Treasury fails enable.
 *
 * <p>Resolves the live {@link TreasuryApi} (plus {@link TaxApi} for sales-tax routing and,
 * optionally, the Business {@link BusinessApi} for firm CHESTSHOP permission checks), finds or
 * creates the ChestShop SYSTEM account used for intermediary transfers, and hands those handles
 * to {@link EconomyService}/{@link BusinessAccountService} — the direct TreasuryApi/BusinessApi
 * boundary that replaced the currency + account event bus. Absorbed the former
 * {@code adapters/TreasuryEconomyProvider} (PAR-307).
 */
@Singleton
@Slf4j
public class TreasuryIntegration implements Integration {

    private final EconomyService economy;
    private final BusinessAccountService businessAccounts;

    @Inject
    public TreasuryIntegration(EconomyService economy, BusinessAccountService businessAccounts) {
        this.economy = economy;
        this.businessAccounts = businessAccounts;
    }

    @Override
    public String pluginName() {
        return "Treasury";
    }

    @Override
    public boolean required() {
        return true;
    }

    @Override
    public boolean hook(Plugin plugin) {
        RegisteredServiceProvider<TreasuryApi> rsp =
                Bukkit.getServicesManager().getRegistration(TreasuryApi.class);
        if (rsp == null) {
            log.warn("Treasury plugin found but TreasuryApi service not registered!");
            return false;
        }
        TreasuryApi treasury = rsp.getProvider();

        // Find or create the ChestShop SYSTEM account for intermediary transfers.
        int systemAccountId;
        try {
            List<Account> systemAccounts =
                    treasury.getAccountsByTypeAndOwner(AccountType.SYSTEM, BusinessAccountUtil.CHESTSHOP_SYSTEM_UUID);
            if (systemAccounts != null && !systemAccounts.isEmpty()) {
                systemAccountId = systemAccounts.get(0).getAccountId();
            } else {
                Account systemAccount =
                        treasury.createAccount(AccountType.SYSTEM, BusinessAccountUtil.CHESTSHOP_SYSTEM_UUID, "ChestShop System");
                systemAccount.setAllowOverdraft(true);
                treasury.updateAccount(systemAccount);
                systemAccountId = systemAccount.getAccountId();
            }
        } catch (Exception e) {
            log.error("Failed to initialize Treasury SYSTEM account!", e);
            return false;
        }
        log.info("Treasury SYSTEM account initialized (ID: " + systemAccountId + ")");

        // Resolve TaxApi for sales-tax routing into Treasury's default tax account (typically
        // DCGovernment). Treasury exposes it as a separate service so we don't need a second lookup.
        TaxApi taxApi = treasury.getTaxApi();
        if (taxApi == null) {
            log.warn(
                    "Treasury loaded but TaxApi unavailable — ChestShop sales tax will not be collected.");
        }
        log.info("Sales tax now routed via Treasury TaxApi → "
                + (taxApi != null ? taxApi.getDefaultTaxAccountName() : "(disabled)"));

        // Optionally integrate with the Business plugin for CHESTSHOP permission checks.
        BusinessApi businessApi = null;
        if (Bukkit.getPluginManager().getPlugin("Business") != null) {
            RegisteredServiceProvider<BusinessApi> businessRsp =
                    Bukkit.getServicesManager().getRegistration(BusinessApi.class);
            if (businessRsp != null) {
                businessApi = businessRsp.getProvider();
                log.info("Business API integrated: firm CHESTSHOP permissions will gate shop access.");
            } else {
                log.warn("Business plugin found but BusinessApi service not registered — falling back to Treasury membership checks.");
            }
        }

        economy.bind(treasury, systemAccountId, taxApi);
        businessAccounts.bind(treasury, businessApi);
        return true;
    }
}
