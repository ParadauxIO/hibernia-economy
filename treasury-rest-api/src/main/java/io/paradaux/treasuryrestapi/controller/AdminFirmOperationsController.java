package io.paradaux.treasuryrestapi.controller;

import io.paradaux.treasuryrestapi.dto.AccountOwnerRequest;
import io.paradaux.treasuryrestapi.security.ActingPlayer;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import io.paradaux.treasuryrestapi.service.AdminFirmOperationsService;
import io.paradaux.treasuryrestapi.service.AdminFirmOperationsService.Result;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Hidden
@RestController
@RequestMapping("/api/v1/admin/firms/{firmId}")
public class AdminFirmOperationsController {
    public record TaxExemptionRequest(@NotNull Boolean exempt) {}

    private final AdminFirmOperationsService service;

    public AdminFirmOperationsController(AdminFirmOperationsService service) {
        this.service = service;
    }

    @PatchMapping("/proprietor")
    public Result proprietor(
            @AuthenticationPrincipal VerifiedToken token,
            @PathVariable long firmId,
            @Valid @RequestBody AccountOwnerRequest request,
            @RequestAttribute(name = ActingPlayer.ATTRIBUTE, required = false) UUID actor) {
        return service.changeProprietor(token, firmId, request.owner(), actor);
    }

    @PostMapping("/release-name")
    public Result releaseName(@AuthenticationPrincipal VerifiedToken token, @PathVariable long firmId) {
        return service.releaseName(token, firmId);
    }

    @PatchMapping("/tax-exemption")
    public Result taxExemption(
            @AuthenticationPrincipal VerifiedToken token,
            @PathVariable long firmId,
            @Valid @RequestBody TaxExemptionRequest request) {
        return service.setTaxExempt(token, firmId, request.exempt());
    }

    @PostMapping("/sync-accounts")
    public Result syncAccounts(
            @AuthenticationPrincipal VerifiedToken token,
            @PathVariable long firmId,
            @RequestAttribute(name = ActingPlayer.ATTRIBUTE, required = false) UUID actor) {
        return service.syncAccounts(token, firmId, actor);
    }
}
