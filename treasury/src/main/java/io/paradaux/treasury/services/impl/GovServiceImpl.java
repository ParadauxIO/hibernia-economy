package io.paradaux.treasury.services.impl;

import com.google.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import io.paradaux.treasury.api.exceptions.FineAlreadyRevokedException;
import io.paradaux.treasury.api.exceptions.FineNotFoundException;
import io.paradaux.treasury.api.exceptions.GovAccountNotFoundException;
import io.paradaux.treasury.api.exceptions.InsufficientFineFundsException;
import io.paradaux.treasury.api.exceptions.PrimitiveAccountException;
import io.paradaux.treasury.mappers.GovernmentFineMapper;
import io.paradaux.treasury.model.config.GovernmentConfiguration;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountType;
import io.paradaux.treasury.model.economy.GovernmentFine;
import io.paradaux.treasury.model.economy.TransferRequest;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.GovService;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.services.PlayerDirectoryService;
import io.paradaux.treasury.utils.Idempotency;
import io.paradaux.treasury.utils.Money;
import io.paradaux.treasury.utils.TreasuryConstants;
import org.mybatis.guice.transactional.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Slf4j
public class GovServiceImpl implements GovService {

    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final GovernmentFineMapper fineMapper;
    private final GovernmentConfiguration govConfig;
    private final PlayerDirectoryService playerDirectory;

    @Inject
    public GovServiceImpl(AccountService accountService,
                          LedgerService ledgerService,
                          GovernmentFineMapper fineMapper,
                          GovernmentConfiguration govConfig,
                          PlayerDirectoryService playerDirectory) {
        this.accountService = accountService;
        this.ledgerService  = ledgerService;
        this.fineMapper     = fineMapper;
        this.govConfig      = govConfig;
        this.playerDirectory = playerDirectory;
    }

    // ---- Account management ----

    @Override
    @Transactional
    public Account createDepartmentAccount(String name, UUID createdBy) {
        Account existing = accountService.getGovernmentAccountByName(name);
        if (existing != null) {
            throw new IllegalArgumentException("A GOVERNMENT account named '" + name + "' already exists");
        }
        // Guard at the source (PAR-144): governments are run as Minecraft player
        // alts of the same name, and that player↔GOVERNMENT name collision is what
        // makes bare-name resolution ambiguous everywhere downstream. Refuse to
        // mint a GOVERNMENT account whose name already belongs to a known player so
        // no new collisions are created.
        if (playerDirectory.resolveUuidByName(name).isPresent()) {
            throw new IllegalArgumentException("A player named '" + name + "' already exists; "
                    + "a GOVERNMENT account must not share a player's name (use a distinct name)");
        }
        Account created = accountService.createAccount(AccountType.GOVERNMENT, TreasuryConstants.VIRTUAL_TREASURY_OWNER, name);
        log.info("Department account '{}' (id={}) created by {}", name, created.getAccountId(), createdBy);
        return created;
    }

    @Override
    @Transactional
    public void archiveDepartmentAccount(String name, UUID archivedBy) {
        if (isPrimitiveName(name)) {
            throw new PrimitiveAccountException(name);
        }
        Account account = accountService.getGovernmentAccountByName(name);
        if (account == null) {
            throw new GovAccountNotFoundException(name);
        }
        accountService.archiveAccount(account.getAccountId());
        log.info("Department account '{}' (id={}) archived by {}", name, account.getAccountId(), archivedBy);
    }

    @Override
    @Transactional
    public List<Account> listGovernmentAccounts() {
        return accountService.listGovernmentAccounts();
    }

    // ---- Fine management ----

    @Override
    @Transactional
    public GovernmentFine issueFine(UUID player, BigDecimal amount, String reason, UUID issuedBy) {
        Money.requirePositive(amount, "fine amount > 0");

        String finesAccountName = govConfig.getFinesAccount();
        Account finesAccount = accountService.getGovernmentAccountByName(finesAccountName);
        if (finesAccount == null) {
            throw new GovAccountNotFoundException(finesAccountName);
        }
        return issueFine(player, finesAccount.getAccountId(), amount, reason, issuedBy);
    }

    @Override
    @Transactional
    public GovernmentFine issueFine(UUID player, int govAccountId, BigDecimal amount, String reason, UUID issuedBy) {
        // Validate the amount before resolving the player so an invalid amount
        // surfaces as an amount error, never "no personal account" (regression
        // tests in GovServiceIT pin this order).
        Money.requirePositive(amount, "fine amount > 0");

        Account playerAccount = accountService.getAccountByUUID(player);
        if (playerAccount == null) {
            throw new IllegalArgumentException("No personal account found for player " + player);
        }
        return doIssueFine(playerAccount.getAccountId(), player, govAccountId, amount, reason, issuedBy);
    }

    @Override
    @Transactional
    public GovernmentFine issueFine(int debtorAccountId, int govAccountId, BigDecimal amount, String reason, UUID issuedBy) {
        Account debtorAccount = accountService.getAccountById(debtorAccountId);
        if (debtorAccount == null) {
            throw new IllegalArgumentException("No account found with id " + debtorAccountId);
        }
        return doIssueFine(debtorAccountId, null, govAccountId, amount, reason, issuedBy);
    }

    /**
     * Core fine issuance: validates the amount and destination, debits the debtor
     * account into the government account, and records the fine. {@code playerUuid}
     * is the fined player for player fines, or {@code null} for firm/account fines.
     */
    private GovernmentFine doIssueFine(int debtorAccountId, UUID playerUuid, int govAccountId,
                                       BigDecimal amount, String reason, UUID issuedBy) {
        Money.requirePositive(amount, "fine amount > 0");

        Account govAccount = accountService.getAccountById(govAccountId);
        if (govAccount == null || govAccount.getAccountType() != AccountType.GOVERNMENT) {
            throw new GovAccountNotFoundException("account id " + govAccountId);
        }

        BigDecimal normalized = Money.normalize(amount);
        // Include the amount and reason in the dedup key, not just issuer+debtor+second:
        // otherwise two legitimate but distinct fines on the same debtor within the same
        // second collapse to one via the ledger dedup UNIQUE (ADT-33). Two fines that
        // also share amount and reason in the same second are treated as a double-submit.
        byte[] dedupKey = Idempotency.sha256("fine:" + issuedBy + ":" + debtorAccountId + ":"
                + normalized.toPlainString() + ":" + reason + ":"
                + Instant.now().truncatedTo(ChronoUnit.SECONDS));

        long txnId;
        try {
            // A fine is an administrative debit by authority of permission, so it
            // must bypass the debtor's requires_authorization gate (you can't dodge
            // a fine by demanding an authorizer) — adminTransfer skips ONLY that gate
            // and still fails on insufficient funds (ADT fine-debtor-auth-not-wrapped).
            txnId = ledgerService.adminTransfer(new TransferRequest(
                    debtorAccountId,
                    govAccount.getAccountId(),
                    normalized,
                    "Fine: " + reason,
                    issuedBy,
                    null,
                    TreasuryConstants.TREASURY_PLUGIN_NAME,
                    dedupKey
            ));
        } catch (IllegalStateException e) {
            // LedgerService.transfer throws IllegalStateException for insufficient
            // funds (and a few other failure modes). The fine path only ever debits
            // the debtor account, so the realistic cause is "can't afford the fine".
            throw playerUuid != null
                    ? new InsufficientFineFundsException(playerUuid, e)
                    : new InsufficientFineFundsException(debtorAccountId, e);
        }

        GovernmentFine fine = GovernmentFine.builder()
                .playerUuid(playerUuid)
                .debtorAccountId(debtorAccountId)
                .govAccountId(govAccount.getAccountId())
                .amount(normalized)
                .reason(reason)
                .txnId(txnId)
                .issuedBy(issuedBy)
                .build();
        fineMapper.insertFine(fine);
        log.info("Fine issued: id={} debtorAccount={} player={} govAccount={} amount={} txn={} issuedBy={}",
                fine.getFineId(), debtorAccountId, playerUuid, govAccount.getAccountId(), normalized, txnId, issuedBy);
        return fine;
    }

    @Override
    @Transactional
    public GovernmentFine revokeFine(long fineId, UUID revokedBy) {
        GovernmentFine fine = fineMapper.findFineById(fineId);
        if (fine == null) {
            throw new FineNotFoundException(fineId);
        }
        if (fine.isRevoked()) {
            throw new FineAlreadyRevokedException(fineId);
        }

        int debtorAccountId = resolveDebtorAccountId(fine);
        Account debtorAccount = accountService.getAccountById(debtorAccountId);
        if (debtorAccount == null) {
            // Keep the player-fine wording (a pinned regression message) when the
            // debtor was a player; firm fines get the account-oriented wording.
            throw new IllegalStateException(fine.getPlayerUuid() != null
                    ? "Player account not found for fine " + fineId
                    : "Debtor account not found for fine " + fineId);
        }
        Account finesAccount = accountService.getAccountById(fine.getGovAccountId());
        if (finesAccount == null) {
            throw new GovAccountNotFoundException("fines account for fine " + fineId);
        }

        byte[] dedupKey = Idempotency.sha256("fine-revoke:" + fineId + ":" + revokedBy);
        long revokeTxnId = ledgerService.transfer(new TransferRequest(
                finesAccount.getAccountId(),
                debtorAccount.getAccountId(),
                fine.getAmount(),
                "Fine revoked: " + fine.getReason(),
                revokedBy,
                null,
                TreasuryConstants.TREASURY_PLUGIN_NAME,
                dedupKey
        ));

        fineMapper.revokeFine(fineId, revokedBy, revokeTxnId);
        fine.setRevoked(true);
        fine.setRevokedBy(revokedBy);
        fine.setRevokeTxnId(revokeTxnId);
        log.info("Fine revoked: id={} debtorAccount={} player={} amount={} reverseTxn={} revokedBy={}",
                fineId, debtorAccountId, fine.getPlayerUuid(), fine.getAmount(), revokeTxnId, revokedBy);
        return fine;
    }

    /**
     * The account to refund on revoke: the stored debtor account, or — for legacy
     * fines predating {@code debtor_account_id} — the fined player's PERSONAL account.
     */
    private int resolveDebtorAccountId(GovernmentFine fine) {
        if (fine.getDebtorAccountId() != null) {
            return fine.getDebtorAccountId();
        }
        Account playerAccount = accountService.getAccountByUUID(fine.getPlayerUuid());
        if (playerAccount == null) {
            throw new IllegalStateException("Player account not found for fine " + fine.getFineId());
        }
        return playerAccount.getAccountId();
    }

    @Override
    @Transactional
    public GovernmentFine getFine(long fineId) {
        return fineMapper.findFineById(fineId);
    }

    @Override
    @Transactional
    public List<GovernmentFine> getPlayerFines(UUID player) {
        return fineMapper.findFinesByPlayer(player);
    }

    @Override
    @Transactional
    public List<GovernmentFine> getActivePlayerFines(UUID player) {
        return fineMapper.findActiveFinesByPlayer(player);
    }

    // ---- Private helpers ----

    private boolean isPrimitiveName(String name) {
        return name.equalsIgnoreCase(govConfig.getStartingBalancesAccount())
                || name.equalsIgnoreCase(govConfig.getTaxIncomeAccount())
                || name.equalsIgnoreCase(govConfig.getFinesAccount());
    }
}
