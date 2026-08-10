package io.paradaux.chestshop.services.impl;
import io.paradaux.chestshop.utils.StringUtil;
import io.paradaux.chestshop.utils.SignText;
import lombok.extern.slf4j.Slf4j;

import io.paradaux.chestshop.services.ItemService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.chestshop.services.BusinessAccountService;
import io.paradaux.chestshop.services.AdminBypassService;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.chestshop.services.EconomyService;
import io.paradaux.chestshop.utils.BusinessAccountUtil;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.business.api.BusinessApi;
import io.paradaux.chestshop.ChestShop;
import io.paradaux.chestshop.utils.Permissions;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.model.Transaction;
import io.paradaux.treasury.api.TaxApi;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.model.economy.AccountType;
import io.paradaux.treasury.model.economy.TransferRequest;
import io.paradaux.treasury.model.tax.TaxResult;
import io.paradaux.treasury.utils.Idempotency;
import org.bukkit.World;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The internal economy API: ChestShop's single point of contact with the Treasury
 * ledger ({@link TreasuryApi}). Services call these methods directly instead of
 * firing the old internal {@code Currency*Event}s and routing them through
 * {@code TreasuryIntegration} — replacing the economy event bus with a
 * treasury-api-style service boundary.
 *
 * <p>The live {@link TreasuryApi} (and optional {@link BusinessApi}) handle + the
 * ChestShop SYSTEM account id are {@linkplain #bind bound} once Treasury is resolved
 * at enable (from {@code TreasuryIntegration.hook}); ChestShop requires
 * Treasury, so by the time any economy call runs they are set. Account resolution,
 * access checks, balances, settlement and legacy business-sign migration all live
 * here now — the {@code Currency*}/{@code Account*} event bus and {@code TreasuryIntegration}'s
 * handlers were collapsed into these direct calls.
 */
@Singleton
@Slf4j
public class EconomyServiceImpl implements EconomyService {

    private static final int MAX_MESSAGE_LENGTH = 250;

    private volatile TreasuryApi treasury;
    private volatile int systemAccountId;
    private volatile TaxApi taxApi;

    private final AccountService accounts;
    private final ItemService items;
    private final ChestShopConfiguration config;

    private final AdminBypassService adminBypass;

    @Inject
    public EconomyServiceImpl(AccountService accounts, ItemService items, ChestShopConfiguration config, AdminBypassService adminBypass) {
        this.adminBypass = adminBypass;
        this.accounts = accounts;
        this.items = items;
        this.config = config;
    }

    /** Wire the resolved Treasury handle + SYSTEM account + tax API in once available (enable time). */
    @Override
    public void bind(TreasuryApi treasury, int systemAccountId, TaxApi taxApi) {
        this.treasury = treasury;
        this.systemAccountId = systemAccountId;
        this.taxApi = taxApi;
    }

    /** Render a money amount for display through Treasury, honouring STRIP_PRICE_COLORS. */
    @Override
    public String format(BigDecimal amount) {
        TreasuryApi t = treasury;
        if (t == null) {
            return amount.toPlainString();
        }
        try {
            String formatted = t.formatAmount(amount);
            return config.isStripPriceColors() ? StringUtil.stripColourCodes(formatted) : formatted;
        } catch (Exception e) {
            log.warn("Treasury: Could not format amount " + amount, e);
            return amount.toPlainString();
        }
    }

    @Override
    public boolean isOwnerEconomicallyActive(boolean unlimitedOwner) {
        return !unlimitedOwner || accounts.getServerEconomyAccount() != null;
    }

    // deposit/withdraw are intentionally NON-idempotent: the dedup key folds in
    // System.nanoTime() so each call is a distinct movement that never collapses onto a
    // previous one. That is correct for their only callers — one-shot shop-creation fees
    // and shop-removal refunds (ShopServiceImpl), where a second legitimate shop create is
    // a genuinely separate charge that must NOT dedup onto the first. Do NOT make these
    // keys deterministic, and do NOT reuse deposit/withdraw from a retryable path without a
    // caller-supplied dedup key — the idempotent trade settlement path uses the private
    // transfer() helper with a deterministic chestshop:settle:<tradeId> key instead.
    @Override
    public boolean deposit(UUID target, BigDecimal amount, World world) {
        UUID resolved = normaliseAdminTarget(target);
        if (resolved == null) {
            return true; // admin shop with no server-economy account → deliberate faucet, treated as success
        }
        try {
            int targetAccountId = resolveAccountId(resolved);
            byte[] dedupKey = Idempotency.sha256(
                    "chestshop:add:" + resolved + ":" + amount + ":" + System.nanoTime());
            treasury.transfer(new TransferRequest(
                    systemAccountId, targetAccountId, amount, "ChestShop deposit",
                    BusinessAccountUtil.CHESTSHOP_SYSTEM_UUID, null, "ChestShop", dedupKey));
            return true;
        } catch (Exception e) {
            log.warn("Treasury: Could not add " + amount + " to " + resolved, e);
            return false;
        }
    }

    @Override
    public boolean withdraw(UUID target, BigDecimal amount, World world) {
        UUID resolved = normaliseAdminTarget(target);
        if (resolved == null) {
            return true; // admin shop with no server-economy account → unlimited
        }
        try {
            int targetAccountId = resolveAccountId(resolved);
            byte[] dedupKey = Idempotency.sha256(
                    "chestshop:sub:" + resolved + ":" + amount + ":" + System.nanoTime());
            // The account owner authorises the withdrawal for personal accounts.
            UUID initiator = BusinessAccountUtil.isBusinessUuid(resolved) ? BusinessAccountUtil.CHESTSHOP_SYSTEM_UUID : resolved;
            treasury.transfer(new TransferRequest(
                    targetAccountId, systemAccountId, amount, "ChestShop withdrawal",
                    initiator, null, "ChestShop", dedupKey));
            return true;
        } catch (Exception e) {
            log.warn("Treasury: Could not subtract " + amount + " from " + resolved, e);
            return false;
        }
    }

    @Override
    public boolean hasFunds(UUID account, BigDecimal amount) {
        UUID resolved = normaliseAdminTarget(account);
        if (resolved == null) {
            return true;
        }
        try {
            return treasury.hasFunds(resolveAccountId(resolved), amount);
        } catch (Exception e) {
            log.warn("Treasury: Could not check funds for " + resolved, e);
            return false;
        }
    }

    @Override
    public BigDecimal getBalance(UUID account) {
        UUID resolved = normaliseAdminTarget(account);
        if (resolved == null) {
            return BigDecimal.valueOf(Double.MAX_VALUE);
        }
        try {
            if (BusinessAccountUtil.isBusinessUuid(resolved)) {
                return treasury.getBalanceByAccountId((int) resolved.getLeastSignificantBits());
            }
            Integer governmentAccountId = resolveGovernmentAccountId(resolved);
            return governmentAccountId != null
                    ? treasury.getBalanceByAccountId(governmentAccountId)
                    : treasury.getBalanceByOwnerUuid(resolved);
        } catch (Exception e) {
            log.warn("Treasury: Could not get balance for " + resolved, e);
            return BigDecimal.ZERO;
        }
    }

    /** Whether {@code account} exists in the Treasury ledger (was {@code AccountCheckEvent}). */
    @Override
    public boolean hasAccount(UUID account) {
        try {
            if (BusinessAccountUtil.isBusinessUuid(account)) {
                return treasury.hasAccountByAccountId((int) account.getLeastSignificantBits());
            }
            Integer governmentAccountId = resolveGovernmentAccountId(account);
            return governmentAccountId != null
                    ? treasury.hasAccountByAccountId(governmentAccountId)
                    : treasury.hasAccountByOwnerUuid(account);
        } catch (Exception e) {
            log.warn("Treasury: Could not check account for " + account, e);
            return false;
        }
    }

    @Override
    public boolean settle(BigDecimal amount, Player initiator, UUID partner, Transaction txn) {
        boolean buy = txn.getTransactionType() == io.paradaux.chestshop.model.Transaction.TransactionType.BUY;
        UUID resolvedPartner = partner;
        if (accounts.isAdminShop(resolvedPartner) && !accounts.isServerEconomyAccount(resolvedPartner)) {
            Account server = accounts.getServerEconomyAccount();
            if (server != null) {
                resolvedPartner = server.getUuid();
            }
        }

        UUID sender = buy ? initiator.getUniqueId() : resolvedPartner;
        UUID receiver = buy ? resolvedPartner : initiator.getUniqueId();
        boolean senderIsAdmin = accounts.isAdminShop(sender);
        boolean receiverIsAdmin = accounts.isAdminShop(receiver);

        String memo = buildTransferMessage(txn);

        UUID tradeId = txn.getTradeId();
        int receiverAccountId = -1;
        long settlementTxnId = 0;
        try {
            if (!senderIsAdmin && !receiverIsAdmin) {
                // Direct buyer → seller transfer — atomic, no SYSTEM hop, no rollback.
                int senderAccountId = resolveAccountId(sender);
                receiverAccountId = resolveAccountId(receiver);
                settlementTxnId = transfer(senderAccountId, receiverAccountId, amount, memo, transferInitiator(sender), tradeId);
            } else if (receiverIsAdmin && !senderIsAdmin) {
                // Paying into an admin shop with no server account → money sink (→ SYSTEM).
                int senderAccountId = resolveAccountId(sender);
                settlementTxnId = transfer(senderAccountId, systemAccountId, amount, memo, transferInitiator(sender), tradeId);
            } else if (senderIsAdmin && !receiverIsAdmin) {
                // An admin shop with no server account pays out → money source (SYSTEM →).
                receiverAccountId = resolveAccountId(receiver);
                settlementTxnId = transfer(systemAccountId, receiverAccountId, amount, memo, BusinessAccountUtil.CHESTSHOP_SYSTEM_UUID, tradeId);
            }
            // both admin → no real accounts, nothing moves
        } catch (Exception e) {
            log.warn("Treasury: Could not settle " + amount + " from " + sender + " to " + receiver, e);
            return false;
        }

        // Link the recorded sale back to its primary money leg's ledger txn (PAR-234).
        // Zero when nothing moved (both sides admin).
        if (settlementTxnId > 0) {
            txn.setSettlementTxnId(settlementTxnId);
        }

        // Sales tax — best-effort; the primary transfer has already committed.
        if (taxApi != null && !receiverIsAdmin && receiverAccountId > 0) {
            // Record the tax actually collected on the event so the market-analytics
            // recorder logs the real figure instead of zero (ADT-130).
            txn.setSalesTax(collectSalesTax(receiverAccountId, receiver, amount, resolvedPartner, initiator, memo, tradeId));
        }
        return true;
    }

    /** @return the ledger txn id of the posted transfer (PAR-234). */
    private long transfer(int from, int to, BigDecimal amount, String memo, UUID initiator, UUID tradeId) {
        // ADT-129: deterministic dedup key anchored on the per-trade nonce, so a
        // double-fired settle of the same trade is collapsed by the ledger's UNIQUE
        // constraint instead of being recorded as a second money movement.
        byte[] dedupKey = Idempotency.sha256("chestshop:settle:" + tradeId);
        return treasury.transfer(new TransferRequest(from, to, amount, memo, initiator, null, "ChestShop", dedupKey));
    }

    private UUID transferInitiator(UUID sender) {
        // A business account authorises through the SYSTEM account; a personal account authorises itself.
        return BusinessAccountUtil.isBusinessUuid(sender) ? BusinessAccountUtil.CHESTSHOP_SYSTEM_UUID : sender;
    }

    /** @return the tax actually collected (ADT-130), or {@code ZERO} if none was charged. */
    private BigDecimal collectSalesTax(int receiverAccountId, UUID receiver, BigDecimal amount, UUID partner, Player initiator, String memo, UUID tradeId) {
        BigDecimal rate = resolveTaxRate(partner);
        if (rate.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        if (initiator != null && adminBypass.has(initiator, Permissions.NO_BUY_TAX)) {
            return BigDecimal.ZERO;
        }
        try {
            UUID initiatorUuid = BusinessAccountUtil.isBusinessUuid(receiver) ? BusinessAccountUtil.CHESTSHOP_SYSTEM_UUID : receiver;
            // ADT-129: deterministic, anchored on the per-trade nonce (distinct
            // prefix from the transfer leg so the trade's two money movements don't
            // collide while a replay of the same trade still dedups).
            byte[] dedupKey = Idempotency.sha256("chestshop:tax:" + tradeId);
            TaxResult result = taxApi.collectRateTax(
                    receiverAccountId, amount, rate, "chestshop-sales-tax",
                    "ChestShop sales tax (" + rate.movePointRight(2).stripTrailingZeros().toPlainString()
                            + "% of " + amount + ") — " + memo,
                    initiatorUuid, "ChestShop", dedupKey);
            if (result instanceof TaxResult.Collected c) {
                return c.amountCharged();
            }
            if (result instanceof TaxResult.Failed f) {
                log.warn(
                        "Treasury: sales-tax collection failed for accountId=" + receiverAccountId + ": " + f.errorMessage());
            }
        } catch (Exception e) {
            log.warn("Treasury: sales-tax collection threw for receiver " + receiver, e);
        }
        return BigDecimal.ZERO;
    }

    /** Tax rate as a fraction: {@code SERVER_TAX_AMOUNT} for admin/server counterparties, else {@code TAX_AMOUNT}. */
    private BigDecimal resolveTaxRate(UUID partner) {
        double pct = (partner != null
                && (accounts.isAdminShop(partner) || accounts.isServerEconomyAccount(partner)))
                ? config.getServerTaxAmount()
                : config.getTaxAmount();
        if (pct == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(pct).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
    }

    private String buildTransferMessage(Transaction txn) {
        int totalItems = Arrays.stream(txn.getStock()).mapToInt(ItemStack::getAmount).sum();
        String itemName = transferItemName(txn);
        String ownerName = txn.getOwnerAccount().getName();
        String clientName = txn.getClient().getName();
        boolean isBuy = txn.getTransactionType() == Transaction.TransactionType.BUY;

        String prefix = clientName + (isBuy ? " bought x" : " sold x") + totalItems + " ";
        String suffix = (isBuy ? " from " : " to ") + ownerName;
        int available = MAX_MESSAGE_LENGTH - prefix.length() - suffix.length();

        if (available < 1) {
            return (prefix + suffix).substring(0, MAX_MESSAGE_LENGTH);
        }
        if (itemName.length() > available) {
            itemName = itemName.substring(0, available);
        }
        return prefix + itemName + suffix;
    }

    private String transferItemName(Transaction txn) {
        ItemStack[] stock = txn.getStock();
        if (stock != null && stock.length > 0 && stock[0] != null) {
            try {
                String code = items.getName(stock[0], 0);
                if (code != null && !code.isBlank()) {
                    return code;
                }
            } catch (RuntimeException ignored) {
                // Code didn't round-trip — fall back to the sign line below.
            }
        }
        return SignService.getItem(txn.getSign());
    }

    /**
     * Apply the admin-shop → server-economy redirect: a plain target is returned
     * unchanged; an admin-shop target becomes the server-economy account's UUID, or
     * {@code null} when no server-economy account is configured (the operation is then
     * a no-op / unlimited, matching the old {@code ServerAccountCorrector}).
     */
    private UUID normaliseAdminTarget(UUID target) {
        if (!accounts.isAdminShop(target) || accounts.isServerEconomyAccount(target)) {
            return target;
        }
        Account server = accounts.getServerEconomyAccount();
        return server != null ? server.getUuid() : null;
    }

    // Business-account resolution/access + the synthetic-UUID codec moved out (PAR-282):
    // resolution → BusinessAccountService (its own API handles, no back-edge to this
    // service — that dissolved the AccountService↔EconomyService cycle); the codec →
    // the shared BusinessAccountUtil, which this service's money routing uses statically.

    @Override
    public void migrateLegacyBusinessSign(Transaction event) {
        Sign sign = event.getSign();
        Account owner = event.getOwnerAccount();
        if (sign == null || owner == null || owner.getUuid() == null || !BusinessAccountUtil.isBusinessUuid(owner.getUuid())) {
            return;
        }

        String canonical = owner.getShortName();
        if (canonical == null || canonical.equals(SignService.getOwner(sign))) {
            return;
        }

        SignText.setLine(sign, SignService.NAME_LINE, canonical);
        sign.update(true);
        log.info("Migrated legacy business shop sign to " + canonical
                + " at " + sign.getLocation());
    }

    private int resolveAccountId(UUID uuid) {
        if (BusinessAccountUtil.isBusinessUuid(uuid)) {
            return (int) uuid.getLeastSignificantBits();
        }
        Integer governmentAccountId = resolveGovernmentAccountId(uuid);
        if (governmentAccountId != null) {
            return governmentAccountId;
        }
        return treasury.resolveOrCreatePersonal(uuid).getAccountId();
    }

    private Integer resolveGovernmentAccountId(UUID uuid) {
        try {
            List<io.paradaux.treasury.model.economy.Account> governmentAccounts =
                    treasury.getAccountsByTypeAndOwner(AccountType.GOVERNMENT, uuid);
            if (governmentAccounts != null && !governmentAccounts.isEmpty()) {
                return governmentAccounts.get(0).getAccountId();
            }
        } catch (Exception e) {
            log.warn("Treasury: government account lookup failed for " + uuid, e);
        }
        return null;
    }
}
