package io.paradaux.treasuryrestapi.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for PATCH /firms/me/accounts/{accountId}/display-name.
 */
public record UpdateDisplayNameRequest(@NotBlank String displayName) {}
