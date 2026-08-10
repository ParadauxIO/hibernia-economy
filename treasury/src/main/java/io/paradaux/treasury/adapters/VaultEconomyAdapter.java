package io.paradaux.treasury.adapters;

import com.google.inject.Inject;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import io.paradaux.treasury.api.TaxApi;
import io.paradaux.treasury.model.config.EconomyConfiguration;
import io.paradaux.treasury.model.tax.TaxResult;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.utils.CallingPluginDetector;
import io.paradaux.treasury.utils.TreasuryConstants;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Full Vault Economy implementation that bridges to the domain services.
 *
 * Vault calls are attributed to the actual plugin calling Vault
 * via CallingPluginDetector – so each plugin can get its own SYSTEM account.
 *
 * After every successful deposit, source income tax is applied automatically
 * via {@link TaxApi#applySourceIncomeTax} if the feature is enabled in config.
 */
@Slf4j
public class VaultEconomyAdapter implements Economy {

    private static final String NAME = "TreasuryEconomy";
    private static final String FALLBACK_PLUGIN_NAME = "TreasuryVaultBridge";
    private static final int FRACTIONAL_DIGITS = 2;

    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final EconomyConfiguration economyConfiguration;
    private final TaxApi taxApi;
    // DecimalFormat is not thread-safe; use a ThreadLocal so each thread gets its own instance.
    private final ThreadLocal<DecimalFormat> formatter;

    @Setter
    private volatile boolean enabled = true;

    @Inject
    public VaultEconomyAdapter(AccountService accountService,
                               LedgerService ledgerService,
                               EconomyConfiguration economyConfiguration,
                               TaxApi taxApi) {
        this.accountService = Objects.requireNonNull(accountService, "accountService");
        this.ledgerService = Objects.requireNonNull(ledgerService, "ledgerService");
        this.economyConfiguration = Objects.requireNonNull(economyConfiguration, "economyConfiguration");
        this.taxApi = Objects.requireNonNull(taxApi, "taxApi");

        String pattern = "###,##0." + "0".repeat(FRACTIONAL_DIGITS);
        this.formatter = ThreadLocal.withInitial(() -> {
            DecimalFormat fmt = new DecimalFormat(pattern);
            fmt.setRoundingMode(RoundingMode.HALF_EVEN);
            return fmt;
        });
    }

    /* ========================= Economy identity / config ========================= */

    @Override public boolean isEnabled() { return enabled; }
    @Override public @NotNull String getName() { return NAME; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return FRACTIONAL_DIGITS; }

    @Override
    public @NotNull String format(double amount) {
        BigDecimal bd = BigDecimal.valueOf(amount).setScale(FRACTIONAL_DIGITS, RoundingMode.HALF_EVEN);
        return formatter.get().format(bd);
    }

    @Override public @NotNull String currencyNamePlural() { return economyConfiguration.getCurrencyNamePlural(); }
    @Override public @NotNull String currencyNameSingular() { return economyConfiguration.getCurrencyNameSingular(); }

    /* ========================= Account existence ========================= */

    @Override public boolean hasAccount(@NotNull String playerName) { return hasAccount(resolveUuid(playerName)); }
    @Override public boolean hasAccount(@NotNull OfflinePlayer player) { return hasAccount(player.getUniqueId()); }
    @Override public boolean hasAccount(@NotNull String playerName, @NotNull String worldName) { return hasAccount(playerName); }
    @Override public boolean hasAccount(@NotNull OfflinePlayer player, @NotNull String worldName) { return hasAccount(player); }

    private boolean hasAccount(@Nullable UUID uuid) {
        if (uuid == null) return false;
        // Redirected UUIDs (legacy "player" DCGovernment / GovReserve / etc.)
        // count as having an account even if no PERSONAL row exists for them
        // — the redirect points at the GOVERNMENT account that fields their
        // Vault calls.
        if (accountService.findRedirectedAccount(uuid).isPresent()) return true;
        return accountService.hasPersonalAccount(uuid);
    }

    /* ========================= Balance ========================= */

    @Override public double getBalance(@NotNull String playerName) { return getBalance(resolveUuid(playerName)); }
    @Override public double getBalance(@NotNull OfflinePlayer player) { return getBalance(player.getUniqueId()); }
    @Override public double getBalance(@NotNull String playerName, @NotNull String world) { return getBalance(playerName); }
    @Override public double getBalance(@NotNull OfflinePlayer player, @NotNull String world) { return getBalance(player); }

    private double getBalance(@Nullable UUID uuid) {
        if (uuid == null) return 0.0D;
        // Redirected UUIDs read from the GOVERNMENT account they point at,
        // not from a PERSONAL account they don't have. Both lookups are
        // cache-backed, so for a known player this runs with a single DB read
        // (the balance) on the caller's (often main) thread.
        Integer accountId = accountService.findRedirectedAccount(uuid).orElse(null);
        if (accountId == null) {
            accountId = accountService.findPersonalAccountId(uuid);
            if (accountId == null) return 0.0D;
        }
        return accountService.getBalanceReadOnly(accountId)
                .setScale(FRACTIONAL_DIGITS, RoundingMode.HALF_EVEN).doubleValue();
    }

    /* ========================= Has funds ========================= */

    @Override public boolean has(@NotNull String playerName, double amount) { return has(resolveUuid(playerName), amount); }
    @Override public boolean has(@NotNull OfflinePlayer player, double amount) { return has(player.getUniqueId(), amount); }
    @Override public boolean has(@NotNull String playerName, @NotNull String worldName, double amount) { return has(playerName, amount); }
    @Override public boolean has(@NotNull OfflinePlayer player, @NotNull String worldName, double amount) { return has(player, amount); }

    private boolean has(@Nullable UUID uuid, double amount) {
        if (uuid == null) return false;
        BigDecimal need = BigDecimal.valueOf(amount).setScale(FRACTIONAL_DIGITS, RoundingMode.HALF_EVEN);
        return BigDecimal.valueOf(getBalance(uuid)).compareTo(need) >= 0;
    }

    /* ========================= Withdraw ========================= */

    @Override public @NotNull EconomyResponse withdrawPlayer(@NotNull String playerName, double amount) { return withdrawPlayer(resolveOffline(playerName), amount); }
    @Override public @NotNull EconomyResponse withdrawPlayer(@NotNull OfflinePlayer player, double amount) { return withdraw(player.getUniqueId(), amount, null); }
    @Override public @NotNull EconomyResponse withdrawPlayer(@NotNull String playerName, @NotNull String worldName, double amount) { return withdrawPlayer(playerName, amount); }
    @Override public @NotNull EconomyResponse withdrawPlayer(@NotNull OfflinePlayer player, @NotNull String worldName, double amount) { return withdrawPlayer(player, amount); }

    private @NotNull EconomyResponse withdraw(@Nullable UUID playerUuid, double amount, @Nullable String memo) {
        if (playerUuid == null) return failure(amount, 0D, "Unknown player");
        if (amount < 0.01D) return failure(amount, getBalance(playerUuid), "Amount must be at least 0.01");
        String pluginKey = resolveCallingPluginKey();
        try {
            BigDecimal bd = BigDecimal.valueOf(amount).setScale(FRACTIONAL_DIGITS, RoundingMode.HALF_EVEN);
            ledgerService.vaultWithdraw(pluginKey, playerUuid, bd,
                    memo != null ? memo : "Vault withdraw (" + pluginKey + ")",
                    TreasuryConstants.VIRTUAL_TREASURY_INITIATOR, null, null);
            return success(amount, getBalance(playerUuid), "OK");
        } catch (Exception e) {
            return failure(amount, getBalance(playerUuid), e.getMessage());
        }
    }

    /* ========================= Deposit ========================= */

    @Override public @NotNull EconomyResponse depositPlayer(@NotNull String playerName, double amount) { return depositPlayer(Objects.requireNonNull(resolveOffline(playerName)), amount); }
    @Override public @NotNull EconomyResponse depositPlayer(@NotNull OfflinePlayer player, double amount) { return deposit(player.getUniqueId(), amount, null); }
    @Override public @NotNull EconomyResponse depositPlayer(@NotNull String playerName, @NotNull String worldName, double amount) { return depositPlayer(playerName, amount); }
    @Override public @NotNull EconomyResponse depositPlayer(@NotNull OfflinePlayer player, @NotNull String worldName, double amount) { return depositPlayer(player, amount); }

    private @NotNull EconomyResponse deposit(@Nullable UUID playerUuid, double amount, @Nullable String memo) {
        if (playerUuid == null) return failure(amount, 0D, "Unknown player");
        if (amount < 0.01D) return failure(amount, getBalance(playerUuid), "Amount must be at least 0.01");
        String pluginKey = resolveCallingPluginKey();
        BigDecimal bd = BigDecimal.valueOf(amount).setScale(FRACTIONAL_DIGITS, RoundingMode.HALF_EVEN);

        // The Vault economy API carries no caller-supplied idempotency token, so
        // deposits are intentionally at-least-once: two identical deposits are a
        // legitimate pattern, not a duplicate, so we deliberately pass no dedupKey.
        // The critical invariant is therefore to report the deposit TRUTHFULLY — a
        // committed deposit must never return FAILURE, or an at-least-once Vault
        // caller will retry and double-credit (money creation, ADT-8).
        try {
            ledgerService.vaultDeposit(pluginKey, playerUuid, bd,
                    memo != null ? memo : "Vault deposit (" + pluginKey + ")",
                    TreasuryConstants.VIRTUAL_TREASURY_INITIATOR, null, null);
        } catch (Exception e) {
            return failure(amount, getBalance(playerUuid), e.getMessage());
        }

        // Source income tax runs only after the deposit has committed and must not
        // affect the deposit's reported outcome. It returns its own result and logs
        // its own failures, so neither a Failed result nor a thrown exception can
        // turn a successful deposit into a FAILURE (which previously triggered the
        // double-credit retry above).
        try {
            TaxResult taxResult = taxApi.applySourceIncomeTax(playerUuid, bd, pluginKey);
            if (taxResult instanceof TaxResult.Failed f) {
                log.warn("Source income tax failed for player {} (plugin={}, amount={}): {}",
                        playerUuid, pluginKey, bd, f.errorMessage());
            }
        } catch (Exception e) {
            log.warn("Source income tax threw for player {} (plugin={}, amount={}) after a committed deposit: {}",
                    playerUuid, pluginKey, bd, e.getMessage());
        }

        return success(amount, getBalance(playerUuid), "OK");
    }

    /* ========================= Bank (not supported) ========================= */

    @Override public @NotNull EconomyResponse createBank(@NotNull String name, @NotNull String player) { return notSupported(); }
    @Override public @NotNull EconomyResponse createBank(@NotNull String name, @NotNull OfflinePlayer player) { return notSupported(); }
    @Override public @NotNull EconomyResponse deleteBank(@NotNull String name) { return notSupported(); }
    @Override public @NotNull EconomyResponse bankBalance(@NotNull String name) { return notSupported(); }
    @Override public @NotNull EconomyResponse bankHas(@NotNull String name, double amount) { return notSupported(); }
    @Override public @NotNull EconomyResponse bankWithdraw(@NotNull String name, double amount) { return notSupported(); }
    @Override public @NotNull EconomyResponse bankDeposit(@NotNull String name, double amount) { return notSupported(); }
    @Override public @NotNull EconomyResponse isBankOwner(@NotNull String name, @NotNull String playerName) { return notSupported(); }
    @Override public @NotNull EconomyResponse isBankOwner(@NotNull String name, @NotNull OfflinePlayer player) { return notSupported(); }
    @Override public @NotNull EconomyResponse isBankMember(@NotNull String name, @NotNull String playerName) { return notSupported(); }
    @Override public @NotNull EconomyResponse isBankMember(@NotNull String name, @NotNull OfflinePlayer player) { return notSupported(); }
    @Override public @NotNull List<String> getBanks() { return Collections.emptyList(); }

    /* ========================= Create player accounts ========================= */

    @Override public boolean createPlayerAccount(@NotNull String playerName) { return createPlayerAccount(Objects.requireNonNull(resolveOffline(playerName))); }
    @Override public boolean createPlayerAccount(@NotNull String playerName, @NotNull String worldName) { return createPlayerAccount(playerName); }
    @Override public boolean createPlayerAccount(@NotNull OfflinePlayer player, @NotNull String worldName) { return createPlayerAccount(player); }

    @Override
    public boolean createPlayerAccount(@NotNull OfflinePlayer player) {
        UUID uuid = player.getUniqueId();
        // Redirected UUIDs already have a target (GOVERNMENT) account.
        // Don't create a PERSONAL row for them — that would defeat the
        // redirect by leaving an unused empty account around.
        if (accountService.findRedirectedAccount(uuid).isPresent()) {
            return false;
        }
        boolean existed = accountService.hasPersonalAccount(uuid);
        ledgerService.resolveOrCreatePersonal(uuid);
        return !existed;
    }

    /* ========================= Helpers ========================= */

    private static @Nullable UUID resolveUuid(@NotNull String playerName) {
        OfflinePlayer off = resolveOffline(playerName);
        return off != null ? off.getUniqueId() : null;
    }

    private static @Nullable OfflinePlayer resolveOffline(@NotNull String playerName) {
        try {
            return Bukkit.getOfflinePlayer(playerName);
        } catch (Throwable t) {
            return null;
        }
    }

    private static EconomyResponse success(double amount, double balance, String msg) {
        return new EconomyResponse(amount, balance, EconomyResponse.ResponseType.SUCCESS, msg);
    }

    private static EconomyResponse failure(double amount, double balance, String msg) {
        return new EconomyResponse(amount, balance, EconomyResponse.ResponseType.FAILURE, msg == null ? "error" : msg);
    }

    private static EconomyResponse notSupported() {
        return new EconomyResponse(0D, 0D, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank operations are not supported");
    }

    private static String resolveCallingPluginKey() {
        return CallingPluginDetector.currentPluginKeyOrDefault(FALLBACK_PLUGIN_NAME);
    }
}
