package io.paradaux.treasuryrestapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Body for creating an owner-scoped webhook subscription (admin, ADT-14). */
public record WebhookCreateRequest(
        @NotNull UUID ownerUuid,
        @NotBlank String keyType,
        Integer accountId,
        Integer firmId,
        @NotBlank String targetUrl,
        @NotBlank String secret) {
}
