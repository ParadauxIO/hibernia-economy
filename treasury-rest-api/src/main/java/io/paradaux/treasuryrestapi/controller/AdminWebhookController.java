package io.paradaux.treasuryrestapi.controller;

import io.paradaux.treasuryrestapi.dto.WebhookActiveRequest;
import io.paradaux.treasuryrestapi.dto.WebhookCreateRequest;
import io.paradaux.treasuryrestapi.dto.WebhookSecretRequest;
import io.paradaux.treasuryrestapi.dto.WebhookUrlRequest;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import io.paradaux.treasuryrestapi.service.AdminWebhookService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * SERVICE-scoped admin management of webhook subscriptions (ADT-14). The optional
 * {@code ownerUuid} query param scopes a mutation to that owner's row (explorer
 * self-service) vs. by-id (fleet admin). Hidden from OpenAPI; gated in
 * {@link AdminWebhookService}.
 */
@Hidden
@RestController
@RequestMapping("/api/v1/admin/webhooks")
public class AdminWebhookController {

    private final AdminWebhookService service;

    public AdminWebhookController(AdminWebhookService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, Long>> create(@AuthenticationPrincipal VerifiedToken verified,
                                                     @Valid @RequestBody WebhookCreateRequest req) {
        return ResponseEntity.ok(Map.of("subscriptionId", service.create(verified, req)));
    }

    @PatchMapping("/{id}/active")
    public ResponseEntity<Map<String, Integer>> setActive(@AuthenticationPrincipal VerifiedToken verified,
                                                          @PathVariable long id,
                                                          @RequestParam(required = false) UUID ownerUuid,
                                                          @Valid @RequestBody WebhookActiveRequest req) {
        return ResponseEntity.ok(Map.of("affected", service.setActive(verified, id, ownerUuid, req.active())));
    }

    @PatchMapping("/{id}/url")
    public ResponseEntity<Map<String, Integer>> setUrl(@AuthenticationPrincipal VerifiedToken verified,
                                                       @PathVariable long id,
                                                       @RequestParam(required = false) UUID ownerUuid,
                                                       @Valid @RequestBody WebhookUrlRequest req) {
        return ResponseEntity.ok(Map.of("affected", service.setUrl(verified, id, ownerUuid, req.targetUrl())));
    }

    @PatchMapping("/{id}/secret")
    public ResponseEntity<Map<String, Integer>> setSecret(@AuthenticationPrincipal VerifiedToken verified,
                                                          @PathVariable long id,
                                                          @RequestParam(required = false) UUID ownerUuid,
                                                          @Valid @RequestBody WebhookSecretRequest req) {
        return ResponseEntity.ok(Map.of("affected", service.setSecret(verified, id, ownerUuid, req.secret())));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Integer>> delete(@AuthenticationPrincipal VerifiedToken verified,
                                                       @PathVariable long id,
                                                       @RequestParam(required = false) UUID ownerUuid) {
        return ResponseEntity.ok(Map.of("affected", service.delete(verified, id, ownerUuid)));
    }
}
