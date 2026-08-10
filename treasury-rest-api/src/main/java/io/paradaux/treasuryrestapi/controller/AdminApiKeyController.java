package io.paradaux.treasuryrestapi.controller;

import io.paradaux.treasuryrestapi.security.VerifiedToken;
import io.paradaux.treasuryrestapi.service.AdminApiKeyService;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * SERVICE-scoped admin operations on API keys (ADT-14): revoke and force-rotate.
 * Hidden from OpenAPI; gated in {@link AdminApiKeyService}.
 */
@Hidden
@RestController
@RequestMapping("/api/v1/admin/api-keys")
public class AdminApiKeyController {

    private final AdminApiKeyService service;

    public AdminApiKeyController(AdminApiKeyService service) {
        this.service = service;
    }

    @PostMapping("/{keyId}/revoke")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal VerifiedToken verified,
                                       @PathVariable long keyId) {
        service.revoke(verified, keyId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{keyId}/rotate")
    public ResponseEntity<Map<String, Instant>> rotate(@AuthenticationPrincipal VerifiedToken verified,
                                                       @PathVariable long keyId) {
        Instant expiresAt = service.rotate(verified, keyId);
        return ResponseEntity.ok(Map.of("expiresAt", expiresAt));
    }
}
