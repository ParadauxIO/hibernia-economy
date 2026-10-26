package io.paradaux.treasuryrestapi.dto;

import jakarta.validation.constraints.NotBlank;

/** Body for rotating a webhook subscription's signing secret (admin, ADT-14). */
public record WebhookSecretRequest(@NotBlank String secret) {
}
