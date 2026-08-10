package io.paradaux.treasuryrestapi.dto;

import jakarta.validation.constraints.NotBlank;

/** Body for changing a webhook subscription's target URL (admin, ADT-14). */
public record WebhookUrlRequest(@NotBlank String targetUrl) {
}
