package io.paradaux.business.services;

import java.util.UUID;

public interface FirmRequestService {

    void offerEmployment(String firmName, UUID playerId, UUID actorId);
    void rescindEmploymentOffer(String firmName, UUID playerId, UUID actorId);
    void acceptEmploymentOffer(String firmName, UUID playerId, UUID actorId);
    void rejectEmploymentOffer(String firmName, UUID playerId, UUID actorId);

    // ---- int-id overloads (structure/0004) ----------------------------------
    // For internal callers (the BusinessApi delegate) that already hold the firm
    // id: resolve straight by id instead of round-tripping through
    // getFirmByNameOrId(String.valueOf(id)). The String overloads stay for the
    // command entrypoints resolving user name-or-id input.

    void offerEmployment(int firmId, UUID playerId, UUID actorId);
    void rescindEmploymentOffer(int firmId, UUID playerId, UUID actorId);
    void acceptEmploymentOffer(int firmId, UUID playerId, UUID actorId);
    void rejectEmploymentOffer(int firmId, UUID playerId, UUID actorId);

    String beginTransferProprietorship(String firmName, UUID newProprietorId, UUID actorId);
    boolean confirmTransferProprietorship(String firmName, UUID newProprietorId, String code, UUID actorId);
    void cancelTransferProprietorship(String firmName, UUID newProprietorId, UUID actorId);

    UUID completeTransferProprietorship(String firmName, UUID newProprietorId);
    UUID rejectTransferProprietorship(String firmName, UUID newProprietorId, UUID actorId);

    /**
     * Expires stale pending transfer requests and employment invites in one pass.
     * Owns the mapper access so the scheduled {@code ExpireRequestsJob} doesn't
     * reach past the service layer into the mapper (plugin-architecture/0005).
     *
     * @return a tally of how many of each were expired
     */
    ExpiryResult expireStale();

    /** Outcome of an {@link #expireStale()} pass. */
    record ExpiryResult(int transfers, int invites) {}

}
