package io.paradaux.treasury.event;

import io.paradaux.treasury.api.TaxApi;
import io.paradaux.treasury.model.tax.TaxCycleType;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.time.Instant;

/**
 * Fired by Treasury on its Bukkit scheduler when a recurring tax cycle begins.
 *
 * <p>Consuming plugins (e.g. Realty, Business) listen for this event and call
 * {@link TaxApi#collectTax} or {@link TaxApi#collectBatch} for each account they
 * need to tax during that cycle. Treasury drives the schedule; plugins own their
 * own tax logic and amounts.
 *
 * <p><b>Threading:</b> this event is dispatched off the main thread (on a Bukkit
 * async task), so handlers run asynchronously. Handlers MUST NOT touch the Bukkit
 * API (worlds, entities, inventories, online-player state) directly — doing so off
 * the main thread risks corrupting server state. The intended work,
 * {@link TaxApi#collectTax} / {@link TaxApi#collectBatch}, is pure database I/O and
 * is safe here; for anything Bukkit-touching, hop back to the main thread first
 * (ADT taxcycle-event-fired-async).
 *
 * <p>Example — collecting weekly property taxes in Realty:
 * <pre>{@code
 * @EventHandler
 * public void onTaxCycle(TaxCycleEvent event) {
 *     if (event.getCycleType() != TaxCycleType.WEEKLY) return;
 *
 *     List<TaxCollection> charges = new ArrayList<>();
 *     for (UUID owner : regionService.getAllOwners()) {
 *         BigDecimal tax = propertyTaxCalculator.compute(owner);
 *         if (tax.compareTo(BigDecimal.ZERO) <= 0) continue;
 *
 *         int accountId = treasuryApi.resolveOrCreatePersonal(owner).getAccountId();
 *         byte[] dedup = Idempotency.sha256("property-tax:" + owner + ":" + event.getPeriodStart());
 *         charges.add(TaxCollection.toDefaultAccount(
 *             accountId, tax, "property-tax", "Weekly Property Tax", INITIATOR, "realty", dedup));
 *     }
 *     event.getTaxApi().collectBatch(charges);
 * }
 * }</pre>
 *
 * <p><b>Thread safety:</b> This event is fired asynchronously by Treasury's scheduler.
 * Do not call Bukkit API methods that are not thread-safe from inside this handler.
 * Database and Treasury API calls are safe.
 */
public class TaxCycleEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final TaxCycleType cycleType;
    private final Instant periodStart;
    private final TaxApi taxApi;

    /**
     * @param cycleType   The schedule that triggered this cycle (DAILY, WEEKLY, MONTHLY).
     * @param periodStart The start instant of this cycle period (use as dedup key component).
     * @param taxApi      Live reference to the tax API for collecting charges inside this handler.
     */
    public TaxCycleEvent(TaxCycleType cycleType, Instant periodStart, TaxApi taxApi) {
        super(true /* async */);
        this.cycleType = cycleType;
        this.periodStart = periodStart;
        this.taxApi = taxApi;
    }

    /** The schedule that triggered this event. */
    public TaxCycleType getCycleType() {
        return cycleType;
    }

    /**
     * The canonical start of this cycle period.
     * Use this (not {@code Instant.now()}) as a component of dedup keys to ensure
     * idempotency if the event is fired more than once for the same period.
     */
    public Instant getPeriodStart() {
        return periodStart;
    }

    /**
     * Live reference to the tax API.
     * Call {@link TaxApi#collectTax} or {@link TaxApi#collectBatch} here to charge accounts.
     */
    public TaxApi getTaxApi() {
        return taxApi;
    }

    // ---- Bukkit event boilerplate ----

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
