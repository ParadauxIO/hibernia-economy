package io.paradaux.chestshop.services;

import io.paradaux.business.api.BusinessApi;
import io.paradaux.chestshop.ChestShop;
import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.model.Transaction;
import io.paradaux.treasury.api.TaxApi;
import io.paradaux.treasury.api.TreasuryApi;
import org.bukkit.World;
import org.bukkit.entity.Player;
import java.math.BigDecimal;
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
public interface EconomyService {

    /** Wire the resolved Treasury handle + SYSTEM account + tax API in once available (enable time). */
    void bind(TreasuryApi treasury, int systemAccountId, TaxApi taxApi);

    /** Render a money amount for display through Treasury, honouring STRIP_PRICE_COLORS. */
    String format(BigDecimal amount);

    /**
     * Whether the shop owner actually moves money on a sell: a normal shop always does; an
     * unlimited admin shop only when a server-economy account is configured (otherwise its
     * infinite side needs no funds check). (Was the static {@code Economy.isOwnerEconomicallyActive}.)
     */
    boolean isOwnerEconomicallyActive(boolean unlimitedOwner);

    /**
     * Credit {@code amount} to {@code target} (a SYSTEM→target transfer). Admin-shop
     * targets are redirected to the configured server-economy account, or swallowed
     * if none is configured (was {@code CurrencyAddEvent} + {@code ServerAccountCorrector}).
     *
     * @return whether the credit was confirmed. A no-op admin-shop credit (no server-economy
     *         account configured — the deliberate faucet) counts as success; only a failed
     *         ledger transfer returns {@code false}, so callers can gate a mirrored leg on it.
     */
    boolean deposit(UUID target, BigDecimal amount, World world);

    /**
     * Debit {@code amount} from {@code target} (a target→SYSTEM transfer). Returns
     * whether the debit succeeded — {@code false} means it could not be taken (e.g.
     * insufficient funds), which the caller treats as "can't afford". Admin-shop
     * targets are redirected to the server-economy account, or treated as an
     * unlimited success if none is configured (was {@code CurrencySubtractEvent} +
     * {@code ServerAccountCorrector}).
     */
    boolean withdraw(UUID target, BigDecimal amount, World world);

    /**
     * Whether {@code account} can afford {@code amount}. An admin shop with no
     * server-economy account is unlimited (was {@code CurrencyCheckEvent}).
     */
    boolean hasFunds(UUID account, BigDecimal amount);

    /**
     * The balance of {@code account}. An admin shop with no server-economy account
     * reports an unlimited balance (was {@code CurrencyAmountEvent}).
     */
    BigDecimal getBalance(UUID account);

    /** Whether {@code account} exists in the Treasury ledger (was {@code AccountCheckEvent}). */
    boolean hasAccount(UUID account);

    /**
     * Settle the money leg of a trade: move {@code amount} from the buyer to the
     * seller. {@code initiator} is the player who clicked the shop and {@code partner}
     * the shop-owner account; {@code buy} selects the direction (a buy pays the owner,
     * a sell pays the client). Returns whether settlement succeeded — {@code false}
     * tells the caller to reverse the goods leg.
     *
     * <p>When both sides are real accounts (the common case) this is a single direct
     * buyer→seller {@link TreasuryApi#transfer} — atomic, so there's no SYSTEM-account
     * hop and no manual rollback. Admin shops have no real account, so they use the
     * ChestShop SYSTEM account as a money sink (paying into an admin shop) or source
     * (an admin shop paying out); an admin shop is first redirected to the configured
     * server-economy account when there is one. Replaces {@code CurrencyTransferEvent}
     * + {@code TreasuryIntegration.onCurrencyTransfer} + {@code ServerAccountCorrector}.
     *
     * <p>Runs synchronously on the main thread (see {@link TransactionService#process}) and is
     * fast-fail by contract (ADT-131): the primary transfer is a single attempt — any failure
     * is caught and returns {@code false} immediately (no retries, no backoff), so a slow or
     * failing ledger degrades to a cancelled trade rather than a held tick. The sales-tax leg
     * is best-effort and runs only after the primary has already committed.
     */
    boolean settle(BigDecimal amount, Player initiator, UUID partner, Transaction txn);

    /**
     * Lazily migrate a legacy business shop sign to the native account-id format.
     * The old PlayerBusinesses chestshops addressed a firm by name ({@code b:<FirmName>});
     * the native format is {@code B:<base36 account id>}. By the time a shop trades, the
     * owner {@link Account} has been resolved and its short name is the canonical native
     * token, so if the physical sign still shows the legacy text we rewrite the owner line
     * in place. A no-op for shops already in the native form (or non-business owners).
     * Runs as a MONITOR-equivalent post-transaction step (was
     * {@code TreasuryIntegration.onTransactionMigrateSign}).
     */
    void migrateLegacyBusinessSign(Transaction event);
}
