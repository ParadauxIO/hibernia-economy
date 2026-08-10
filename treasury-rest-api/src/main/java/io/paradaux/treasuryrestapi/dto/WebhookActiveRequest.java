package io.paradaux.treasuryrestapi.dto;

/** Body for enabling/disabling a webhook subscription (admin, ADT-14). */
public record WebhookActiveRequest(boolean active) {
}
