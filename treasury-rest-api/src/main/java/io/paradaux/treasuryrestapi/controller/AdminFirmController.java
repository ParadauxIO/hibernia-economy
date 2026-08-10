package io.paradaux.treasuryrestapi.controller;

import io.paradaux.treasuryrestapi.dto.FirmDisbandResponse;
import io.paradaux.treasuryrestapi.dto.FirmRenameRequest;
import io.paradaux.treasuryrestapi.dto.FirmResponse;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import io.paradaux.treasuryrestapi.service.AdminFirmService;
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
 * Privileged admin firm operations (disband / rename), driven by the
 * economy-explorer admin tool with a SERVICE-scoped key.
 *
 * <p>{@link Hidden}: this controller is intentionally excluded from the OpenAPI
 * document / Swagger UI — it is an internal, undocumented surface, not part of
 * the public API contract. The endpoints still require a valid Bearer token and
 * are gated to SERVICE keys in {@link AdminFirmService}.
 */
@Hidden
@RestController
@RequestMapping("/api/v1/admin/firms")
public class AdminFirmController {

    private static final Logger log = LoggerFactory.getLogger(AdminFirmController.class);

    private final AdminFirmService adminFirmService;

    public AdminFirmController(AdminFirmService adminFirmService) {
        this.adminFirmService = adminFirmService;
    }

    /** POST /api/v1/admin/firms/{firmId}/disband — sweep balances, archive accounts + firm. */
    @PostMapping("/{firmId}/disband")
    public ResponseEntity<FirmDisbandResponse> disband(@AuthenticationPrincipal VerifiedToken verified,
                                                       @PathVariable long firmId) {
        log.info("POST /admin/firms/{}/disband requested by keyId={}",
                firmId, verified != null ? verified.keyId() : null);
        return ResponseEntity.ok(adminFirmService.disband(verified, firmId));
    }

    /** POST /api/v1/admin/firms/{firmId}/rename — rename with the in-game name rules. */
    @PostMapping("/{firmId}/rename")
    public ResponseEntity<FirmResponse> rename(@AuthenticationPrincipal VerifiedToken verified,
                                               @PathVariable long firmId,
                                               @Valid @RequestBody FirmRenameRequest request) {
        log.info("POST /admin/firms/{}/rename requested by keyId={}",
                firmId, verified != null ? verified.keyId() : null);
        return ResponseEntity.ok(adminFirmService.rename(verified, firmId, request.newName()));
    }

    /** PATCH /api/v1/admin/firms/{firmId} — update business details (HQ region, Discord URL). */
    @PatchMapping("/{firmId}")
    public ResponseEntity<FirmResponse> updateDetails(@AuthenticationPrincipal VerifiedToken verified,
                                                      @PathVariable long firmId,
                                                      @RequestBody io.paradaux.treasuryrestapi.dto.FirmDetailsRequest request) {
        log.info("PATCH /admin/firms/{} (details) requested by keyId={}",
                firmId, verified != null ? verified.keyId() : null);
        return ResponseEntity.ok(adminFirmService.updateDetails(verified, firmId, request.discordUrl(), request.hqRegion()));
    }
}
