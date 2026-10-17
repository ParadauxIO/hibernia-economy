package io.paradaux.treasuryrestapi.service;

import io.paradaux.treasuryrestapi.dto.FirmAccountSummaryResponse;
import io.paradaux.treasuryrestapi.dto.FirmBalanceResponse;
import io.paradaux.treasuryrestapi.dto.FirmEmployeeResponse;
import io.paradaux.treasuryrestapi.dto.FirmResponse;
import io.paradaux.treasuryrestapi.dto.FirmRoleResponse;
import io.paradaux.treasuryrestapi.dto.FirmUpdateRequest;
import io.paradaux.treasuryrestapi.dto.PublicFirmResponse;
import io.paradaux.treasuryrestapi.exception.ApiException;
import io.paradaux.treasuryrestapi.mapper.FirmMapper;
import io.paradaux.treasuryrestapi.model.Firm;
import io.paradaux.treasuryrestapi.model.FirmAccountSummary;
import io.paradaux.treasuryrestapi.model.FirmEmployee;
import io.paradaux.treasuryrestapi.model.FirmRole;
import io.paradaux.treasuryrestapi.model.FirmRolePermissionRow;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class FirmService {

    private static final Logger log = LoggerFactory.getLogger(FirmService.class);

    // Column bounds from the canonical schema. Pre-validating against them turns an
    // over-long value into a clean 400 instead of a driver-level data-truncation 500.
    /** {@code accounts.display_name} / {@code firm.display_name} VARCHAR(255). */
    private static final int MAX_DISPLAY_NAME_LENGTH = 255;
    // discord_url / hq_region width limits live in FirmFieldLimits (ADT-120).

    private final FirmMapper firmMapper;

    public FirmService(FirmMapper firmMapper) {
        this.firmMapper = firmMapper;
    }

    /** Public firm profile — any authenticated caller, looked up by display name. */
    public PublicFirmResponse getPublicFirm(String displayName) {
        Firm firm = firmMapper.findFirmByDisplayName(displayName);
        if (firm == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FIRM_NOT_FOUND", "Firm not found.");
        }
        return new PublicFirmResponse(
                firm.getFirmId(),
                firm.getDisplayName(),
                firm.getDiscordUrl(),
                firm.getHqRegion(),
                firm.getDefaultAccountId(),
                firm.isArchived(),
                // ADT-119: null-guard rather than NPE into a 500 if created_at is ever null.
                firm.getCreatedAt() != null ? firm.getCreatedAt().toInstant(ZoneOffset.UTC) : null
        );
    }

    public List<FirmEmployeeResponse> listEmployees(VerifiedToken verified) {
        requireBusinessKey(verified);
        return firmMapper.listFirmEmployees(verified.firmId()).stream()
                .map(this::toEmployeeResponse)
                .toList();
    }

    public List<FirmRoleResponse> listRoles(VerifiedToken verified) {
        requireBusinessKey(verified);

        List<FirmRole> roles = firmMapper.listFirmRoles(verified.firmId());
        Map<Integer, List<String>> permsByRole = firmMapper.listFirmRolePermissions(verified.firmId())
                .stream()
                .collect(Collectors.groupingBy(
                        FirmRolePermissionRow::getRoleId,
                        Collectors.mapping(FirmRolePermissionRow::getPermission, Collectors.toList())
                ));

        return roles.stream()
                .map(r -> new FirmRoleResponse(
                        r.getName(),
                        r.getRankOrder(),
                        r.isProprietorLike(),
                        r.isDefaultRole(),
                        permsByRole.getOrDefault(r.getRoleId(), List.of())
                ))
                .toList();
    }

    public FirmBalanceResponse getFirmBalance(String displayName) {
        Firm firm = firmMapper.findFirmByDisplayName(displayName);
        if (firm == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FIRM_NOT_FOUND", "Firm not found.");
        }
        java.math.BigDecimal total = firmMapper.sumFirmBalance(firm.getFirmId());
        return new FirmBalanceResponse(firm.getFirmId(), firm.getDisplayName(), total.toPlainString());
    }

    public FirmResponse getFirm(VerifiedToken verified) {
        requireBusinessKey(verified);
        Firm firm = loadFirm(verified.firmId());
        return toResponse(firm);
    }

    /**
     * Applies a partial update to the firm.
     * Only non-null fields in the request are changed; existing values are kept otherwise.
     * An empty string {@code ""} can be used to clear {@code discordUrl} or {@code hqRegion}.
     */
    public FirmResponse updateFirm(VerifiedToken verified, FirmUpdateRequest request) {
        requireBusinessKey(verified);

        if (request.discordUrl() == null && request.hqRegion() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BODY",
                    "At least one field must be provided: discordUrl, hqRegion.");
        }
        // Validate against the column widths before touching the DB (ADT-120: shared
        // with AdminFirmService). emptyToNull strips before storing, so the validated
        // stripped length matches what lands.
        FirmFieldLimits.validate(request.discordUrl(), request.hqRegion());

        Firm firm = loadFirm(verified.firmId());

        String newDiscordUrl = request.discordUrl() != null ? FirmFieldLimits.emptyToNull(request.discordUrl()) : firm.getDiscordUrl();
        String newHqRegion   = request.hqRegion()   != null ? FirmFieldLimits.emptyToNull(request.hqRegion())   : firm.getHqRegion();

        firmMapper.updateFirm(firm.getFirmId(), firm.getDisplayName(), newDiscordUrl, newHqRegion);

        log.info("Firm updated: firmId={} by keyId={}", firm.getFirmId(), verified.keyId());

        firm.setDiscordUrl(newDiscordUrl);
        firm.setHqRegion(newHqRegion);
        return toResponse(firm);
    }

    public List<FirmAccountSummaryResponse> listAccounts(VerifiedToken verified) {
        requireBusinessKey(verified);
        List<FirmAccountSummary> accounts = firmMapper.listFirmAccounts(verified.firmId());
        return accounts.stream().map(this::toAccountSummaryResponse).toList();
    }

    /**
     * Updates the display name of an account that belongs to the firm.
     * Returns the updated account summary.
     */
    public FirmAccountSummaryResponse updateAccountDisplayName(VerifiedToken verified,
                                                               long accountId,
                                                               String displayName) {
        requireBusinessKey(verified);

        if (displayName == null || displayName.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BODY",
                    "Field 'displayName' must not be blank.");
        }
        if (displayName.strip().length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BODY",
                    "Field 'displayName' must be at most " + MAX_DISPLAY_NAME_LENGTH + " characters.");
        }

        if (firmMapper.isFirmAccount(verified.firmId(), accountId) == 0) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Account does not belong to the firm associated with this API key.");
        }

        firmMapper.updateAccountDisplayName(accountId, displayName.strip());
        log.info("Account display name updated: accountId={} firmId={} by keyId={}",
                accountId, verified.firmId(), verified.keyId());

        // Reload to return the current state (balance may have changed since the list was fetched)
        List<FirmAccountSummary> accounts = firmMapper.listFirmAccounts(verified.firmId());
        return accounts.stream()
                .filter(a -> a.getAccountId() == accountId)
                .map(this::toAccountSummaryResponse)
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND",
                        "Account not found."));
    }

    // -------------------------------------------------------------------------

    private void requireBusinessKey(VerifiedToken verified) {
        if (!"BUSINESS".equals(verified.keyType())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "This endpoint requires a BUSINESS API key.");
        }
    }

    private Firm loadFirm(long firmId) {
        Firm firm = firmMapper.findFirmById(firmId);
        if (firm == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FIRM_NOT_FOUND", "Firm not found.");
        }
        return firm;
    }

    private FirmResponse toResponse(Firm firm) {
        return new FirmResponse(
                firm.getFirmId(),
                firm.getDisplayName(),
                firm.getDiscordUrl(),
                firm.getHqRegion(),
                firm.isArchived()
        );
    }

    private FirmEmployeeResponse toEmployeeResponse(FirmEmployee e) {
        return new FirmEmployeeResponse(
                e.getPlayerUuid().toString(),
                e.getPlayerName(),
                e.getRoleName(),
                // ADT-119: null-guard rather than NPE into a 500 if joined_at is ever null.
                e.getJoinedAt() != null ? e.getJoinedAt().toInstant(ZoneOffset.UTC) : null
        );
    }

    private FirmAccountSummaryResponse toAccountSummaryResponse(FirmAccountSummary a) {
        return new FirmAccountSummaryResponse(
                a.getAccountId(),
                a.getDisplayName(),
                a.getAccountType(),
                a.getBalance().toPlainString(),
                a.isArchived()
        );
    }
}
