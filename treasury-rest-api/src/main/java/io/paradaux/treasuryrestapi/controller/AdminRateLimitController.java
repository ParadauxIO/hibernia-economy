package io.paradaux.treasuryrestapi.controller;

import io.paradaux.treasuryrestapi.dto.RateLimitOverrideRequest;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import io.paradaux.treasuryrestapi.service.AdminRateLimitService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * SERVICE-scoped admin management of per-issuer rate-limit overrides (ADT-14).
 * Hidden from OpenAPI; gated in {@link AdminRateLimitService}.
 */
@Hidden
@RestController
@RequestMapping("/api/v1/admin/rate-limit-overrides")
public class AdminRateLimitController {

    private final AdminRateLimitService service;

    public AdminRateLimitController(AdminRateLimitService service) {
        this.service = service;
    }

    @PutMapping("/{ownerUuid}")
    public ResponseEntity<Void> set(@AuthenticationPrincipal VerifiedToken verified,
                                    @PathVariable UUID ownerUuid,
                                    @Valid @RequestBody RateLimitOverrideRequest req) {
        service.setOverride(verified, ownerUuid, req.multiplier(), req.note());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{ownerUuid}")
    public ResponseEntity<Void> clear(@AuthenticationPrincipal VerifiedToken verified,
                                      @PathVariable UUID ownerUuid) {
        service.clearOverride(verified, ownerUuid);
        return ResponseEntity.noContent().build();
    }
}
