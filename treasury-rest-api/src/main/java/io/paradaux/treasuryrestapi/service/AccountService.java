package io.paradaux.treasuryrestapi.service;

import io.paradaux.treasuryrestapi.dto.AccountBalanceResponse;
import io.paradaux.treasuryrestapi.dto.AccountByPlayerResponse;
import io.paradaux.treasuryrestapi.exception.ApiException;
import io.paradaux.treasuryrestapi.mapper.AccountMapper;
import io.paradaux.treasuryrestapi.mapper.FirmMapper;
import io.paradaux.treasuryrestapi.model.AccountBalance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Account reads that aren't transaction history: current balance and the
 * player → personal-account resolution. Owns the persistence access and the
 * name/UUID resolution that previously sat in {@code AccountController}.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountMapper accountMapper;
    private final FirmMapper firmMapper;

    public AccountService(AccountMapper accountMapper, FirmMapper firmMapper) {
        this.accountMapper = accountMapper;
        this.firmMapper = firmMapper;
    }

    /**
     * Returns the current balance of an account. Any authenticated caller may
     * query any account.
     *
     * @throws ApiException 404 if the account does not exist
     */
    public AccountBalanceResponse getBalance(long accountId) {
        AccountBalance balance = accountMapper.findBalance(accountId);
        if (balance == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found.");
        }
        return new AccountBalanceResponse(accountId, balance.getBalance().toPlainString());
    }

    /**
     * Resolves a player to their non-archived PERSONAL account. Exactly one of
     * {@code uuid} or {@code name} must be supplied. Names are matched
     * case-insensitively via the {@code economy_players} IGN cache, so a player
     * must have been seen on the server at least once for name lookup to work
     * (UUID lookup has no such constraint).
     *
     * @throws ApiException 400 if not exactly one of uuid/name is given, or the
     *                      uuid is malformed; 404 if the player or their personal
     *                      account cannot be found
     */
    public AccountByPlayerResponse resolvePlayerAccount(String uuid, String name) {
        boolean hasUuid = uuid != null && !uuid.isBlank();
        boolean hasName = name != null && !name.isBlank();
        if (hasUuid == hasName) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUERY",
                    "Provide exactly one of 'uuid' or 'name'.");
        }

        UUID playerUuid;
        if (hasUuid) {
            try {
                playerUuid = UUID.fromString(uuid.trim());
            } catch (IllegalArgumentException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUERY",
                        "Query parameter 'uuid' is not a valid UUID.");
            }
        } else {
            String trimmed = name.trim();
            playerUuid = firmMapper.findPlayerUuidByName(trimmed);
            if (playerUuid == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "PLAYER_NOT_FOUND",
                        "No player known by the name '" + name + "'.");
            }
            // PAR-144: governments run as player alts of the same name. A bare name
            // that is both a player and a non-archived GOVERNMENT account is
            // ambiguous — refuse rather than silently returning the personal
            // account; the caller should query by uuid to disambiguate.
            if (accountMapper.existsGovernmentAccountByName(trimmed)) {
                throw new ApiException(HttpStatus.CONFLICT, "AMBIGUOUS_NAME",
                        "'" + name + "' is both a player and a government account; query by 'uuid' to disambiguate.");
            }
        }

        Long accountId = accountMapper.findPersonalAccountIdByOwner(playerUuid);
        if (accountId == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND",
                    "The target player has no personal account.");
        }

        String playerName = hasName ? name.trim() : firmMapper.findPlayerNameByUuid(playerUuid);

        log.debug("Resolved player {} → accountId={}", playerUuid, accountId);

        return new AccountByPlayerResponse(accountId, playerUuid.toString(), playerName);
    }
}
