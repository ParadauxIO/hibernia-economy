package io.paradaux.treasuryrestapi.controller;

import io.paradaux.treasuryrestapi.dto.CreateWebhookRequest;
import io.paradaux.treasuryrestapi.dto.CreateWebhookResponse;
import io.paradaux.treasuryrestapi.dto.UpdateWebhookRequest;
import io.paradaux.treasuryrestapi.dto.WebhookListResponse;
import io.paradaux.treasuryrestapi.dto.WebhookResponse;
import io.paradaux.treasuryrestapi.ratelimit.RateLimit;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import io.paradaux.treasuryrestapi.service.WebhookService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service webhook subscriptions for the transaction feed. Each subscription
 * is scoped to the calling API key (its account, or firm for BUSINESS keys); the
 * background dispatcher POSTs new settled transactions to the registered URL,
 * HMAC-signed with the per-subscription secret.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WebhookService webhookService;

    public WebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping
    @RateLimit(personalPerMinute = 20, businessPerMinute = 60)
    public ResponseEntity<CreateWebhookResponse> create(
            @AuthenticationPrincipal VerifiedToken verified,
            @Valid @RequestBody CreateWebhookRequest request) {
        log.info("POST /webhooks by keyId={}", verified.keyId());
        CreateWebhookResponse response = webhookService.create(verified, request != null ? request.url() : null);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @RateLimit(personalPerMinute = 60, businessPerMinute = 120)
    public ResponseEntity<WebhookListResponse> list(@AuthenticationPrincipal VerifiedToken verified) {
        return ResponseEntity.ok(new WebhookListResponse(webhookService.list(verified)));
    }

    @GetMapping("/{id}")
    @RateLimit(personalPerMinute = 60, businessPerMinute = 120)
    public ResponseEntity<WebhookResponse> get(@AuthenticationPrincipal VerifiedToken verified,
                                               @PathVariable long id) {
        return ResponseEntity.ok(webhookService.get(verified, id));
    }

    @PatchMapping("/{id}")
    @RateLimit(personalPerMinute = 20, businessPerMinute = 60)
    public ResponseEntity<WebhookResponse> update(@AuthenticationPrincipal VerifiedToken verified,
                                                  @PathVariable long id,
                                                  @RequestBody UpdateWebhookRequest request) {
        String url = request != null ? request.url() : null;
        Boolean active = request != null ? request.active() : null;
        return ResponseEntity.ok(webhookService.update(verified, id, url, active));
    }

    @DeleteMapping("/{id}")
    @RateLimit(personalPerMinute = 20, businessPerMinute = 60)
    public ResponseEntity<Void> delete(@AuthenticationPrincipal VerifiedToken verified,
                                       @PathVariable long id) {
        webhookService.delete(verified, id);
        return ResponseEntity.noContent().build();
    }
}
