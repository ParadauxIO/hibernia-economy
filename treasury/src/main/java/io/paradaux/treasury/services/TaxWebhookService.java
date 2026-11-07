package io.paradaux.treasury.services;

import io.paradaux.treasury.internal.model.tax.TaxCycleReport;

public interface TaxWebhookService {
    /**
     * Posts a tax cycle report to the configured Discord webhook.
     * No-ops silently when the webhook is disabled or URL is blank.
     */
    void sendCycleReport(TaxCycleReport report);
}
