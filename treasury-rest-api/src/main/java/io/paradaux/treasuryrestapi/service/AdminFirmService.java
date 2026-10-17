package io.paradaux.treasuryrestapi.service;

import io.paradaux.treasuryrestapi.dto.FirmDisbandResponse;
import io.paradaux.treasuryrestapi.dto.FirmDisbandResponse.DisbandedAccount;
import io.paradaux.treasuryrestapi.dto.FirmResponse;
import io.paradaux.treasuryrestapi.dto.TransferResponse;
import io.paradaux.treasuryrestapi.exception.ApiException;
import io.paradaux.treasuryrestapi.mapper.AccountMapper;
import io.paradaux.treasuryrestapi.mapper.FirmMapper;
import io.paradaux.treasuryrestapi.model.Firm;
import io.paradaux.treasuryrestapi.model.FirmAccountSummary;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Privileged, cross-firm admin operations (disband / rename) consumed by the
 * economy-explorer admin firm tool. Faithfully reproduces the in-game
 * business-rian control flow ({@code FirmServiceImpl}), but here the ledger and
 * the firm tables share one database, so the whole disband runs in a single
 * transaction — atomic, with none of the plugin's Treasury-IPC orphan risk.
 *
 * <p>Every method requires a SERVICE-scoped key (gated by {@link #requireServiceKey}).
 */
@Service
public class AdminFirmService {

    private static final Logger log = LoggerFactory.getLogger(AdminFirmService.class);

    /** Memo recorded on each disband sweep, mirroring the plugin's transfer reason. */
    private static final String DISBAND_MEMO = "Firm disbanded";

    // Firm-name rules, mirroring business-rian's NameValidator / validateFirmName.
    private static final int NAME_MIN = 2;
    private static final int NAME_MAX = 32;
    private static final Pattern FIRM_NAME = Pattern.compile("[A-Za-z0-9 _.\\-]{" + NAME_MIN + "," + NAME_MAX + "}");

    private final FirmMapper firmMapper;
    private final AccountMapper accountMapper;
    private final TransferService transferService;

    public AdminFirmService(FirmMapper firmMapper, AccountMapper accountMapper, TransferService transferService) {
        this.firmMapper = firmMapper;
        this.accountMapper = accountMapper;
        this.transferService = transferService;
    }

    /**
     * Disbands a firm: sweep each live account's positive balance to the proprietor's
     * personal account (through the ledger), archive each account, soft-delete the
     * firm↔account link, then archive the firm. Atomic and idempotent — a firm that
     * is already archived returns a no-op success, and a partially-applied run can
     * never persist (single transaction).
     */
    @Transactional
    public FirmDisbandResponse disband(VerifiedToken verified, long firmId) {
        requireServiceKey(verified);

        Firm firm = firmMapper.findFirmById(firmId);
        if (firm == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FIRM_NOT_FOUND", "Firm not found.");
        }
        if (firm.isArchived()) {
            // Already disbanded — idempotent no-op so a retried request is safe.
            log.info("Disband no-op: firmId={} already archived", firmId);
            return new FirmDisbandResponse(firmId, firm.getDisplayName(), true, List.of());
        }

        UUID proprietor = firm.getProprietorUuid();
        // Resolve the proprietor's PERSONAL account lazily — only needed if there is a
        // positive balance to move. Unlike the plugin we never MINT one via REST (no
        // faucet exposure); a balance with no destination is a clean 422.
        Long proprietorPersonal = proprietor == null
                ? null : accountMapper.findPersonalAccountIdByOwner(proprietor);

        List<FirmAccountSummary> accounts = firmMapper.listFirmAccounts(firmId); // live links only
        List<DisbandedAccount> results = new ArrayList<>(accounts.size());

        for (FirmAccountSummary account : accounts) {
            long accountId = account.getAccountId();
            String swept = null;
            Long destination = null;

            if (proprietorPersonal != null) {
                // Sweep the FRESHLY LOCKED balance, not the listFirmAccounts snapshot: a
                // concurrent credit/debit landing after the snapshot must not strand a
                // residual or overdraw. sweepAll reads the amount under SELECT ... FOR
                // UPDATE and returns null when the locked balance is zero-or-negative.
                // No per-transfer idempotency key: the whole disband is one DB transaction
                // (ledger + firm tables share this database), so a partial run can't
                // persist and a retried disband is a no-op via the already-archived guard.
                TransferResponse receipt = transferService.sweepAll(
                        accountId, proprietorPersonal, DISBAND_MEMO, proprietor);
                if (receipt != null) {
                    swept = receipt.amount();
                    destination = proprietorPersonal;
                }
            } else {
                // No destination to receive the money — but the snapshot may be stale, so
                // read the LOCKED balance before concluding the account is empty. A real
                // positive balance with nowhere to go is a clean 422 (the whole tx rolls
                // back). MINT is never exposed via REST, so we cannot conjure a personal
                // account the way the in-game plugin can.
                BigDecimal locked = transferService.lockedBalance(accountId);
                if (locked != null && locked.signum() > 0) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "PROPRIETOR_NO_PERSONAL_ACCOUNT",
                            "Firm account " + accountId + " has a positive balance but the proprietor has no "
                                    + "personal account to receive it.");
                }
            }

            accountMapper.archiveAccount(accountId);
            firmMapper.removeFirmAccount(firmId, accountId);
            results.add(new DisbandedAccount(accountId, swept, destination, true));
        }

        firmMapper.archiveFirm(firmId);
        log.info("Disbanded firmId={} ({} account(s) processed) by keyId={}",
                firmId, results.size(), verified.keyId());

        return new FirmDisbandResponse(firmId, firm.getDisplayName(), true, results);
    }

    /**
     * Renames a firm, applying the in-game name rules (2–32 chars, allowed charset,
     * no leading digit) and a case-insensitive uniqueness check (a case-only
     * self-rename is allowed). Archived firms cannot be renamed.
     */
    @Transactional
    public FirmResponse rename(VerifiedToken verified, long firmId, String newName) {
        requireServiceKey(verified);

        Firm firm = firmMapper.findFirmById(firmId);
        if (firm == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FIRM_NOT_FOUND", "Firm not found.");
        }
        if (firm.isArchived()) {
            throw new ApiException(HttpStatus.CONFLICT, "FIRM_ARCHIVED", "Cannot rename an archived firm.");
        }
        validateName(newName);
        // Allow a no-op/case rename of the firm itself; reject collisions with a different firm.
        if (!newName.equalsIgnoreCase(firm.getDisplayName())
                && firmMapper.countOtherFirmsByDisplayName(firmId, newName) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "NAME_TAKEN",
                    "A firm named '" + newName + "' already exists.");
        }

        firmMapper.renameFirm(firmId, newName);
        log.info("Renamed firmId={} to '{}' by keyId={}", firmId, newName, verified.keyId());

        firm.setDisplayName(newName);
        return new FirmResponse(firm.getFirmId(), firm.getDisplayName(),
                firm.getDiscordUrl(), firm.getHqRegion(), firm.isArchived());
    }

    /**
     * Update a firm's business details (HQ region, Discord URL) — admin version of
     * the firm-scoped {@code PATCH /firms/me}. Either field may be null to leave it
     * unchanged, or blank to clear it. Display name is preserved (rename has its own
     * endpoint with the name rules).
     */
    @Transactional
    public FirmResponse updateDetails(VerifiedToken verified, long firmId, String discordUrl, String hqRegion) {
        requireServiceKey(verified);
        if (discordUrl == null && hqRegion == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BODY",
                    "At least one of 'discordUrl' or 'hqRegion' must be provided.");
        }
        FirmFieldLimits.validate(discordUrl, hqRegion); // ADT-120: shared with FirmService
        Firm firm = firmMapper.findFirmById(firmId);
        if (firm == null) throw new ApiException(HttpStatus.NOT_FOUND, "FIRM_NOT_FOUND", "Firm not found.");

        String newDiscord = discordUrl != null ? FirmFieldLimits.emptyToNull(discordUrl) : firm.getDiscordUrl();
        String newHq = hqRegion != null ? FirmFieldLimits.emptyToNull(hqRegion) : firm.getHqRegion();
        firmMapper.updateFirm(firmId, firm.getDisplayName(), newDiscord, newHq);
        log.info("Admin updated firm details firmId={} by keyId={}", firmId, verified.keyId());

        return new FirmResponse(firm.getFirmId(), firm.getDisplayName(), newDiscord, newHq, firm.isArchived());
    }

    // -------------------------------------------------------------------------
    // discord_url / hq_region width limits + emptyToNull live in FirmFieldLimits (ADT-120).

    private void requireServiceKey(VerifiedToken verified) {
        if (verified == null || !"SERVICE".equals(verified.keyType())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "This endpoint requires a SERVICE API key.");
        }
    }

    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BODY", "Field 'newName' is required.");
        }
        if (name.length() < NAME_MIN || name.length() > NAME_MAX) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BODY",
                    "Field 'newName' must be between " + NAME_MIN + " and " + NAME_MAX + " characters.");
        }
        if (Character.isDigit(name.charAt(0))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BODY",
                    "Field 'newName' must not start with a digit.");
        }
        if (!FIRM_NAME.matcher(name).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BODY",
                    "Field 'newName' may only contain letters, digits, spaces, '_', '.' and '-'.");
        }
    }
}
