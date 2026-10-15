package io.paradaux.treasuryrestapi.controller;

import io.paradaux.treasuryrestapi.dto.TaxConfigResponse;
import io.paradaux.treasuryrestapi.ratelimit.RateLimit;
import io.paradaux.treasuryrestapi.service.EconomyConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, read-only view of server configuration the Treasury plugin publishes
 * (PAR-80) — the same live tax rates the explorer docs show. Unauthenticated
 * like the ChestShop market endpoints: these are the rates every player is
 * charged, not per-entity data. {@code JwtAuthFilter} skips this prefix, so
 * callers are throttled by client IP.
 */
@RestController
@RequestMapping("/api/v1/config")
public class ConfigController {

    private final EconomyConfigService economyConfigService;

    public ConfigController(EconomyConfigService economyConfigService) {
        this.economyConfigService = economyConfigService;
    }

    /**
     * GET /api/v1/config/tax
     * Balance-tax brackets and source-income-tax rates currently in force. A
     * section is null until the server has published it.
     */
    @GetMapping("/tax")
    @RateLimit(personalPerMinute = 120, businessPerMinute = 600, anonymousPerMinute = 120)
    public ResponseEntity<TaxConfigResponse> tax() {
        return ResponseEntity.ok(economyConfigService.taxConfig());
    }
}
