package io.paradaux.treasuryrestapi.controller;

import io.paradaux.treasuryrestapi.dto.AccountDisplayNameRequest;
import io.paradaux.treasuryrestapi.dto.AccountOwnerRequest;
import io.paradaux.treasuryrestapi.model.AccountAdminSummary;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import io.paradaux.treasuryrestapi.service.AdminAccountService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SERVICE-scoped admin operations on individual accounts (PAR-217): rename, change
 * owner, archive/unarchive. Hidden from OpenAPI; gated in {@link AdminAccountService}.
 */
@Hidden
@RestController
@RequestMapping("/api/v1/admin/accounts")
public class AdminAccountController {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountController.class);

    private final AdminAccountService accounts;

    public AdminAccountController(AdminAccountService accounts) {
        this.accounts = accounts;
    }

    @PatchMapping("/{id}/display-name")
    public ResponseEntity<AccountAdminSummary> rename(@AuthenticationPrincipal VerifiedToken verified,
                                                      @PathVariable long id,
                                                      @Valid @RequestBody AccountDisplayNameRequest req) {
        log.info("PATCH /admin/accounts/{}/display-name by keyId={}", id, verified != null ? verified.keyId() : null);
        return ResponseEntity.ok(accounts.rename(verified, id, req.displayName()));
    }

    @PatchMapping("/{id}/owner")
    public ResponseEntity<AccountAdminSummary> changeOwner(@AuthenticationPrincipal VerifiedToken verified,
                                                           @PathVariable long id,
                                                           @Valid @RequestBody AccountOwnerRequest req) {
        log.info("PATCH /admin/accounts/{}/owner by keyId={}", id, verified != null ? verified.keyId() : null);
        return ResponseEntity.ok(accounts.changeOwner(verified, id, req.owner()));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<AccountAdminSummary> archive(@AuthenticationPrincipal VerifiedToken verified,
                                                       @PathVariable long id) {
        log.info("POST /admin/accounts/{}/archive by keyId={}", id, verified != null ? verified.keyId() : null);
        return ResponseEntity.ok(accounts.archive(verified, id));
    }

    @PostMapping("/{id}/unarchive")
    public ResponseEntity<AccountAdminSummary> unarchive(@AuthenticationPrincipal VerifiedToken verified,
                                                         @PathVariable long id) {
        log.info("POST /admin/accounts/{}/unarchive by keyId={}", id, verified != null ? verified.keyId() : null);
        return ResponseEntity.ok(accounts.unarchive(verified, id));
    }
}
