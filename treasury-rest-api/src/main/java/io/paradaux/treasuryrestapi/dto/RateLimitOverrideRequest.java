package io.paradaux.treasuryrestapi.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** Body for setting a per-issuer rate-limit multiplier override (admin). */
public record RateLimitOverrideRequest(
        @NotNull BigDecimal multiplier,
        String note) {
}
