package io.paradaux.chestshop.services;

import org.bukkit.event.block.Action;
import io.paradaux.chestshop.ChestShop;
import io.paradaux.chestshop.model.PendingTransaction;
import io.paradaux.chestshop.model.Transaction;
import org.bukkit.Bukkit;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import java.util.UUID;

/**
 * Owns a shop trade end to end: pre-trade validation ({@link #validate}), the atomic
 * goods+money settlement ({@link #execute}), and the post-trade reactions
 * ({@link #process}). This replaces ChestShop's old "Bukkit events as middleware"
 * design — a {@code PendingTransaction}/{@code Transaction} fanned out to ~20
 * priority-ordered listener classes coordinating through a mutable bag — with one
 * service whose steps are ordinary, ordered private methods (PAR-282). It is the
 * atomicity guarantee ADT-4 asked for, readable and unit-testable in one place.
 *
 * <p>The money leg settles directly through {@link ChestShop#economy()} (a single
 * buyer→seller {@code TreasuryApi} transfer); the goods are reversed if it fails, so a
 * trade is all-or-nothing. The genuine cross-cutting hooks (market-DB sync,
 * stock counter) and the cross-plugin {@code RestrictedSignService} access gate stay.
 */
public interface TransactionService {

    /**
     * Build the {@link PendingTransaction} for a shop interaction: resolve the owner
     * account, price the trade (honouring shift-sell-in-stacks / shift-sell-everything), and
     * assemble the stacked items + (virtual admin) shop inventory. Returns {@code null} — after
     * messaging the player — when the click can't become a trade. First step of the trade
     * lifecycle (prepare -> validate -> process -> execute); moved off the PlayerInteractListener
     * listener so the whole lifecycle lives in this service (PAR-299).
     */
    PendingTransaction prepare(Sign sign, Player player, Action action);

    /**
     * Run a shop interaction through the pre-transaction validation steps, mutating the
     * {@link PendingTransaction} context (cancelling it, or adjusting its stock/price)
     * as needed. The steps run in the exact order the former priority-ordered validators
     * fired; each self-guards on {@code isCancelled} where the original did.
     * {@code PartialTransactionModule} and the whole-amount {@code checkFundsAndStock}
     * are config-selected alternatives ({@code ALLOW_PARTIAL_TRANSACTIONS}).
     */
    void validate(PendingTransaction ctx);

    /** Drop a player's notification-cooldown rows (called from PlayerConnectListener on quit). */
    void clearNotificationCooldowns(UUID playerUuid);

    /**
     * Run the post-transaction pipeline for a validated trade, in the former priority +
     * registration order: {@link #execute} the goods+money legs atomically (may cancel);
     * the stock counter refreshes regardless; then, only if the trade settled, the empty
     * shop is removed and the MONITOR reactions (legacy-sign migration, market sync, log,
     * messages, metrics) run.
     *
     * <p><b>Main-thread contract (ADT-131).</b> This runs synchronously on the Bukkit main
     * thread from the {@code PlayerInteractListener} handler, and most of it must: goods move via
     * the main-thread-only inventory API, and the money legs are settled inline so the
     * goods can be reversed if settlement fails ({@link #execute}) — a trade is all-or-nothing.
     * That makes the per-click {@code TreasuryApi.transfer} (one MariaDB write, two with tax)
     * a synchronous cost; settlement is fast-fail (no retries, see {@code EconomyService.settle})
     * and benefits from a warm Treasury connection pool, but it cannot be moved off-thread
     * without breaking the goods/money atomicity. The market sync ({@code market.onTransaction})
     * also stays on-thread because {@code MarketApi.recordSale} fires a synchronous Bukkit
     * event ({@code ChestShopSaleEvent}), which Paper forbids from an async context. The one
     * deferrable step — the shop-log file write — is dispatched async in {@link #logTransaction}.
     * A fuller async redesign would need a goods-move → async-settle → main-thread-reverse
     * handshake and is deliberately out of scope here (tail-latency, not a correctness bug).
     */
    void process(Transaction event);

    /**
     * Run a validated transaction to completion: move the goods, then settle the money —
     * reversing the goods if the (pre-validated, so exceptional) money leg fails, so the
     * trade is all-or-nothing.
     */
    void execute(Transaction event);
}
