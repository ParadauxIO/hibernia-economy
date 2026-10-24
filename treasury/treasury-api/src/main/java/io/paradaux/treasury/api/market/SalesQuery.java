package io.paradaux.treasury.api.market;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.UUID;

/**
 * Read query over {@code chestshop_sale} for the in-game sales commands
 * (PAR-176). <strong>Exactly one</strong> owner scope must be set ({@link #firmId},
 * {@link #ownerUuid} or {@link #accountId}) — the API rejects a query with none
 * (would expose server-wide sales) or with more than one (the scopes are distinct
 * granularities and would AND to an empty result). The remaining fields are
 * optional filters; a null filter means "no constraint". Build with
 * {@code SalesQuery.builder()}.
 */
@Getter
@Builder
@ToString
public class SalesQuery {

    // ---- owner scope (denormalised on the row; no firm→account resolution) ----
    /** Firm whose shops to read ({@code shop_firm_id}). */
    private final Integer firmId;
    /** Personal owner whose shops to read ({@code shop_owner_uuid_bin}). */
    private final UUID ownerUuid;
    /** A specific shop account ({@code shop_account_id}). */
    private final Integer accountId;

    // ---- filters ----
    /** Only sales within the last N days; null = all time. */
    private final Integer windowDays;
    /** "BUY" or "SELL" (customer's perspective); null = both. */
    private final String direction;
    /** Exact item key; null = all items. */
    private final String itemKey;
    /** Only sales to this customer; null = all customers. */
    private final UUID customerUuid;

    // ---- paging ----
    @Builder.Default
    private final int limit = 10;
    @Builder.Default
    private final int offset = 0;
}
