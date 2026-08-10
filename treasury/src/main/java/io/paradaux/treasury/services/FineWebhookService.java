package io.paradaux.treasury.services;

import io.paradaux.treasury.model.economy.GovernmentFine;

/**
 * Posts Discord notifications when a government fine is issued or revoked,
 * mirroring the tax-cycle webhook. All methods no-op silently when the fine
 * webhook is disabled or its URL is blank, and never throw — a notification
 * failure must not disrupt the fine itself.
 */
public interface FineWebhookService {

    /** Announce a newly issued fine. */
    void sendFineIssued(GovernmentFine fine);

    /** Announce a revoked (refunded) fine. */
    void sendFineRevoked(GovernmentFine fine);
}
