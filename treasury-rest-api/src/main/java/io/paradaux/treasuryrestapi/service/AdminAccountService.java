package io.paradaux.treasuryrestapi.service;

import io.paradaux.treasuryrestapi.exception.ApiException;
import io.paradaux.treasuryrestapi.mapper.AccountMapper;
import io.paradaux.treasuryrestapi.mapper.FirmMapper;
import io.paradaux.treasuryrestapi.model.Account;
import io.paradaux.treasuryrestapi.model.AccountAdminSummary;
import io.paradaux.treasuryrestapi.security.AdminScope;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * SERVICE-scoped admin operations on individual accounts: rename, change owner,
 * archive/unarchive. Consumed by the economy-explorer admin account tool (PAR-221).
 */
@Service
public class AdminAccountService {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountService.class);
    private static final int MAX_DISPLAY_NAME_LENGTH = 255; // accounts.display_name VARCHAR(255)

    private final AccountMapper accountMapper;
    private final FirmMapper firmMapper;

    public AdminAccountService(AccountMapper accountMapper, FirmMapper firmMapper) {
        this.accountMapper = accountMapper;
        this.firmMapper = firmMapper;
    }

    @Transactional
    public AccountAdminSummary rename(VerifiedToken verified, long accountId, String displayName) {
        AdminScope.require(verified);
        if (displayName == null || displayName.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BODY", "Field 'displayName' must not be blank.");
        }
        if (displayName.strip().length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BODY",
                    "Field 'displayName' must be at most " + MAX_DISPLAY_NAME_LENGTH + " characters.");
        }
        requireAccount(accountId);
        accountMapper.updateDisplayName(accountId, displayName.strip());
        log.info("Admin renamed accountId={} by keyId={}", accountId, verified.keyId());
        return summary(accountId);
    }

    /**
     * Reassign an account's owner. {@code ownerSpec} is a UUID or a player name
     * (resolved via economy_players). Guards the one-PERSONAL-per-player unique index:
     * a PERSONAL account can't be moved to a player who already has one.
     */
    @Transactional
    public AccountAdminSummary changeOwner(VerifiedToken verified, long accountId, String ownerSpec) {
        AdminScope.require(verified);
        if (ownerSpec == null || ownerSpec.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BODY", "Field 'owner' (UUID or player name) is required.");
        }
        requireAccount(accountId);
        UUID newOwner = resolveOwner(ownerSpec.strip());

        String type = accountMapper.findAccountType(accountId);
        if ("PERSONAL".equals(type)) {
            Long existing = accountMapper.findPersonalAccountIdByOwner(newOwner);
            if (existing != null && existing != accountId) {
                throw new ApiException(HttpStatus.CONFLICT, "PERSONAL_ACCOUNT_EXISTS",
                        "That player already has a personal account (#" + existing + "); a PERSONAL account can't be reassigned to them.");
            }
        }
        accountMapper.updateOwner(accountId, newOwner);
        log.info("Admin changed owner of accountId={} to {} by keyId={}", accountId, newOwner, verified.keyId());
        return summary(accountId);
    }

    @Transactional
    public AccountAdminSummary archive(VerifiedToken verified, long accountId) {
        AdminScope.require(verified);
        requireAccount(accountId);
        accountMapper.archiveAccount(accountId);
        log.info("Admin archived accountId={} by keyId={}", accountId, verified.keyId());
        return summary(accountId);
    }

    @Transactional
    public AccountAdminSummary unarchive(VerifiedToken verified, long accountId) {
        AdminScope.require(verified);
        requireAccount(accountId);
        accountMapper.unarchiveAccount(accountId);
        log.info("Admin unarchived accountId={} by keyId={}", accountId, verified.keyId());
        return summary(accountId);
    }

    // -------------------------------------------------------------------------

    private void requireAccount(long accountId) {
        Account a = accountMapper.findById(accountId);
        if (a == null) throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found.");
    }

    private UUID resolveOwner(String spec) {
        try {
            return UUID.fromString(spec);
        } catch (IllegalArgumentException ignored) {
            UUID byName = firmMapper.findPlayerUuidByName(spec);
            if (byName == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "PLAYER_NOT_FOUND",
                        "No player known by the name '" + spec + "' (and it isn't a UUID).");
            }
            return byName;
        }
    }

    private AccountAdminSummary summary(long accountId) {
        return accountMapper.findAdminSummary(accountId);
    }
}
