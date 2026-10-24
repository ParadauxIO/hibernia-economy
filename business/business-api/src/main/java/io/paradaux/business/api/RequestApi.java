package io.paradaux.business.api;

import java.util.UUID;

/**
 * Employment offer operations.
 *
 * <p>Methods may throw {@link RuntimeException} subclasses for invalid operations
 * (e.g., firm not found, no pending offer, insufficient permissions).
 */
public interface RequestApi {

    /**
     * Offers employment to a player at a firm.
     *
     * @param firmId   the firm ID
     * @param playerId the player's UUID
     * @param actorId  UUID of the player making the offer
     */
    void offerEmployment(int firmId, UUID playerId, UUID actorId);

    /**
     * Rescinds a pending employment offer.
     *
     * @param firmId   the firm ID
     * @param playerId the player whose offer is being rescinded
     * @param actorId  UUID of the player rescinding the offer
     */
    void rescindOffer(int firmId, UUID playerId, UUID actorId);

    /**
     * Accepts a pending employment offer.
     *
     * <p>The acting player must be the offer's target: implementations reject the
     * call when {@code actorId != playerId} so an offer cannot be accepted on
     * another player's behalf.
     *
     * @param firmId   the firm ID
     * @param playerId the player whose offer is being accepted (the offer target)
     * @param actorId  UUID of the player performing the action; must equal {@code playerId}
     */
    void acceptOffer(int firmId, UUID playerId, UUID actorId);

    /**
     * Rejects a pending employment offer.
     *
     * <p>The acting player must be the offer's target: implementations reject the
     * call when {@code actorId != playerId} so an offer cannot be rejected on
     * another player's behalf.
     *
     * @param firmId   the firm ID
     * @param playerId the player whose offer is being rejected (the offer target)
     * @param actorId  UUID of the player performing the action; must equal {@code playerId}
     */
    void rejectOffer(int firmId, UUID playerId, UUID actorId);
}
