package io.paradaux.treasuryrestapi.controller;

import io.paradaux.treasuryrestapi.dto.FirmAccountSummaryResponse;
import io.paradaux.treasuryrestapi.dto.FirmBalanceResponse;
import io.paradaux.treasuryrestapi.dto.FirmEmployeeResponse;
import io.paradaux.treasuryrestapi.dto.FirmResponse;
import io.paradaux.treasuryrestapi.dto.FirmRoleResponse;
import io.paradaux.treasuryrestapi.dto.FirmUpdateRequest;
import io.paradaux.treasuryrestapi.dto.PublicFirmResponse;
import io.paradaux.treasuryrestapi.dto.UpdateDisplayNameRequest;
import io.paradaux.treasuryrestapi.ratelimit.RateLimit;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import io.paradaux.treasuryrestapi.service.FirmService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/firms")
public class FirmController {

    private static final Logger log = LoggerFactory.getLogger(FirmController.class);

    private final FirmService firmService;

    public FirmController(FirmService firmService) {
        this.firmService = firmService;
    }

    /**
     * GET /api/v1/firms/{firmName}
     * Returns public profile for the named firm, including its default account ID.
     * Any authenticated caller may use this endpoint.
     */
    @GetMapping("/{firmName}")
    @RateLimit(personalPerMinute = 60, businessPerMinute = 300)
    public ResponseEntity<PublicFirmResponse> getPublicFirm(
            @AuthenticationPrincipal VerifiedToken verified,
            @PathVariable String firmName) {

        log.info("GET /firms/{} requested by keyId={}", firmName, verified.keyId());

        PublicFirmResponse response = firmService.getPublicFirm(firmName);

        log.info("Returning public profile for firmId={}", response.firmId());
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/firms/{firmName}/balance
     * Returns the summed balance across all accounts owned by the named firm.
     * Any authenticated caller may use this endpoint.
     */
    @GetMapping("/{firmName}/balance")
    @RateLimit(personalPerMinute = 60, businessPerMinute = 300)
    public ResponseEntity<FirmBalanceResponse> getFirmBalance(
            @AuthenticationPrincipal VerifiedToken verified,
            @PathVariable String firmName) {

        log.info("GET /firms/{}/balance requested by keyId={}", firmName, verified.keyId());

        FirmBalanceResponse response = firmService.getFirmBalance(firmName);

        log.info("Returning total balance for firmId={}: {}", response.firmId(), response.totalBalance());
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/firms/me
     * Returns the firm associated with the calling BUSINESS API key.
     */
    @GetMapping("/me")
    @RateLimit(personalPerMinute = 60, businessPerMinute = 300)
    public ResponseEntity<FirmResponse> getFirm(@AuthenticationPrincipal VerifiedToken verified) {
        log.info("GET /firms/me requested by keyId={} firmId={}", verified.keyId(), verified.firmId());

        FirmResponse response = firmService.getFirm(verified);

        log.info("Returning firm: firmId={} displayName={}", response.firmId(), response.displayName());
        return ResponseEntity.ok(response);
    }

    /**
     * PATCH /api/v1/firms/me
     * Updates firm attributes. All fields are optional; omitted fields are unchanged.
     * Pass {@code ""} for {@code discordUrl} or {@code hqRegion} to clear them.
     */
    @PatchMapping("/me")
    @RateLimit(personalPerMinute = 15, businessPerMinute = 60)
    public ResponseEntity<FirmResponse> updateFirm(
            @AuthenticationPrincipal VerifiedToken verified,
            @RequestBody FirmUpdateRequest request) {

        log.info("PATCH /firms/me requested by keyId={} firmId={}", verified.keyId(), verified.firmId());

        FirmResponse response = firmService.updateFirm(verified, request);

        log.info("Firm updated: firmId={}", response.firmId());
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/firms/me/accounts
     * Lists all accounts owned by the firm, with current balances.
     */
    @GetMapping("/me/accounts")
    @RateLimit(personalPerMinute = 60, businessPerMinute = 300)
    public ResponseEntity<List<FirmAccountSummaryResponse>> listAccounts(
            @AuthenticationPrincipal VerifiedToken verified) {

        log.info("GET /firms/me/accounts requested by keyId={} firmId={}", verified.keyId(), verified.firmId());

        List<FirmAccountSummaryResponse> accounts = firmService.listAccounts(verified);

        log.info("Returning {} accounts for firmId={}", accounts.size(), verified.firmId());
        return ResponseEntity.ok(accounts);
    }

    /**
     * GET /api/v1/firms/me/employees
     * Lists all currently employed staff with their role names.
     */
    @GetMapping("/me/employees")
    @RateLimit(personalPerMinute = 60, businessPerMinute = 300)
    public ResponseEntity<List<FirmEmployeeResponse>> listEmployees(
            @AuthenticationPrincipal VerifiedToken verified) {

        log.info("GET /firms/me/employees requested by keyId={} firmId={}", verified.keyId(), verified.firmId());

        List<FirmEmployeeResponse> employees = firmService.listEmployees(verified);

        log.info("Returning {} employees for firmId={}", employees.size(), verified.firmId());
        return ResponseEntity.ok(employees);
    }

    /**
     * GET /api/v1/firms/me/roles
     * Lists all roles defined for the firm, with their permissions.
     */
    @GetMapping("/me/roles")
    @RateLimit(personalPerMinute = 60, businessPerMinute = 300)
    public ResponseEntity<List<FirmRoleResponse>> listRoles(
            @AuthenticationPrincipal VerifiedToken verified) {

        log.info("GET /firms/me/roles requested by keyId={} firmId={}", verified.keyId(), verified.firmId());

        List<FirmRoleResponse> roles = firmService.listRoles(verified);

        log.info("Returning {} roles for firmId={}", roles.size(), verified.firmId());
        return ResponseEntity.ok(roles);
    }

    /**
     * PATCH /api/v1/firms/me/accounts/{accountId}/display-name
     * Updates the display name of an account that belongs to this firm.
     */
    @PatchMapping("/me/accounts/{accountId}/display-name")
    @RateLimit(personalPerMinute = 15, businessPerMinute = 60)
    public ResponseEntity<FirmAccountSummaryResponse> updateAccountDisplayName(
            @AuthenticationPrincipal VerifiedToken verified,
            @PathVariable long accountId,
            @Valid @RequestBody UpdateDisplayNameRequest request) {

        log.info("PATCH /firms/me/accounts/{}/display-name requested by keyId={} firmId={}",
                accountId, verified.keyId(), verified.firmId());

        FirmAccountSummaryResponse response = firmService.updateAccountDisplayName(
                verified, accountId, request.displayName());

        log.info("Account display name updated: accountId={} displayName={}", accountId, response.displayName());
        return ResponseEntity.ok(response);
    }
}
