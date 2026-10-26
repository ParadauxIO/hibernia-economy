package io.paradaux.treasuryrestapi.dto;

import jakarta.validation.constraints.NotBlank;

/** Request body for {@code POST /api/v1/webhooks}. */
public record CreateWebhookRequest(@NotBlank String url) {}
