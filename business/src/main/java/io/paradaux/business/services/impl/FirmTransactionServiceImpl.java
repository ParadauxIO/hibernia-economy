package io.paradaux.business.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.hibernia.framework.exceptions.BadCommandException;
import io.paradaux.hibernia.framework.exceptions.ConflictException;
import io.paradaux.hibernia.framework.exceptions.ExceedsLimitException;
import io.paradaux.hibernia.framework.exceptions.NoPermissionException;
import io.paradaux.hibernia.framework.exceptions.NotFoundException;
import io.paradaux.business.model.Firm;
import io.paradaux.business.model.FirmPlayer;
import io.paradaux.business.services.FirmPlayerService;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.services.FirmTransactionService;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.model.Page;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountMember;
import io.paradaux.treasury.model.economy.TransactionEntry;
import io.paradaux.treasury.model.economy.TransferRequest;

import io.paradaux.business.model.FirmAccount;
import io.paradaux.business.model.FirmBalanceEntry;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Singleton
public class FirmTransactionServiceImpl implements FirmTransactionService {

    // User-facing failures are signalled with the framework's semantic exceptions
    // (plugin-architecture/0001); each carries a messages.properties key so the
    // framework's ErrorRenderer resolves the player text in the sender's locale.
    // Handlers no longer catch-and-hand-format (plugin-architecture/0002).
    private static final String KEY_INVALID_AMOUNT = "business.finance.invalid-amount";
    private static final String KEY_INSUFFICIENT_PERSONAL = "business.finance.insufficient-personal";
    private static final String KEY_INSUFFICIENT_BUSINESS = "business.finance.insufficient-business";
    private static final String KEY_NO_PERMISSION = "business.general.no-permission";
    private static final String KEY_NOT_AUTHORIZER = "business.finance.not-authorizer";
    private static final String KEY_NO_ACCOUNT = "business.finance.no-account";
    private static final String KEY_FOREIGN_ACCOUNT = "business.finance.foreign-account";
    private static final String KEY_ARCHIVED = "business.finance.archived";
    private static final String KEY_SAME_FIRM = "business.finance.pay.same-firm";

    private final FirmService firms;
    private final TreasuryApi treasury;
    private final io.paradaux.business.mappers.FirmAccountsMapper firmAccounts;
    private final FirmPlayerService players;

    @Inject
    public FirmTransactionServiceImpl(FirmService firms, TreasuryApi treasury,
                                      io.paradaux.business.mappers.FirmAccountsMapper firmAccounts,
                                      FirmPlayerService players) {
        this.firms = firms;
        this.treasury = treasury;
        this.firmAccounts = firmAccounts;
        this.players = players;
    }

    @Override
    public BigDecimal getFirmBalance(Integer firmId) {
        int accountId = resolveAccountId(firmId);
        return treasury.getBalanceByAccountId(accountId);
    }

    @Override
    public String getFormattedBalance(Integer firmId) {
        BigDecimal balance = getFirmBalance(firmId);
        return treasury.formatAmount(balance);
    }

    @Override
    public Page<TransactionEntry> getTransactions(Integer firmId, int page, int pageSize) {
        if (page < 1) page = 1;
        if (pageSize < 1) pageSize = 10;
        int accountId = resolveAccountId(firmId);
        int offset = (page - 1) * pageSize;
        return treasury.getTransactionHistory(accountId, offset, pageSize);
    }

    @Override
    public long deposit(Integer firmId, UUID playerUuid, BigDecimal amount) {
        return deposit(firmId, playerUuid, amount, null);
    }

    @Override
    public long deposit(Integer firmId, UUID playerUuid, BigDecimal amount, String memo) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadCommandException(KEY_INVALID_AMOUNT);
        }

        int businessAccountId = resolveAccountId(firmId);
        Account personal = treasury.resolveOrCreatePersonal(playerUuid);

        if (!treasury.hasFunds(personal.getAccountId(), amount)) {
            throw new ExceedsLimitException(KEY_INSUFFICIENT_PERSONAL);
        }

        if (!treasury.canAccessAccount(playerUuid, businessAccountId)) {
            throw new NoPermissionException(KEY_NO_PERMISSION);
        }

        TransferRequest req = new TransferRequest(
                personal.getAccountId(),
                businessAccountId,
                amount,
                depositReason(memo),
                playerUuid,
                null,
                "BusinessPlugin",
                null
        );

        return treasury.transfer(req);
    }

    /**
     * Builds the transfer reason for a deposit, optionally appending a player-supplied
     * memo (PAR-10).
     */
    private static String depositReason(String memo) {
        return reasonWithMemo("Business deposit", memo);
    }

    /**
     * Appends an optional player-supplied memo to a transfer reason. The reason column
     * is VARCHAR(255), so the memo is sanitized (whitespace collapsed, trimmed) and the
     * whole string is capped to fit. A blank/null memo yields {@code base} unchanged.
     */
    private static String reasonWithMemo(String base, String memo) {
        if (memo == null) {
            return base;
        }
        String cleaned = memo.replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) {
            return base;
        }
        String reason = base + ": " + cleaned;
        // Truncate on a code-point boundary to the column's 255-character capacity so a
        // surrogate pair (e.g. an emoji) at the cap can't be split mid-character, and so we
        // measure characters not UTF-16 units (ADT memo-truncation-mid-character).
        if (reason.codePointCount(0, reason.length()) > 255) {
            reason = reason.substring(0, reason.offsetByCodePoints(0, 255));
        }
        return reason;
    }

    @Override
    public long withdraw(Integer firmId, UUID playerUuid, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadCommandException(KEY_INVALID_AMOUNT);
        }

        int businessAccountId = resolveAccountId(firmId);
        Account businessAccount = treasury.getAccountById(businessAccountId);
        Account personal = treasury.resolveOrCreatePersonal(playerUuid);

        if (!treasury.canAccessAccount(playerUuid, businessAccountId)) {
            throw new NoPermissionException(KEY_NO_PERMISSION);
        }

        if (!treasury.hasFunds(businessAccountId, amount)) {
            throw new ExceedsLimitException(KEY_INSUFFICIENT_BUSINESS);
        }

        // If the business account requires authorization, verify the player is an authorizer
        UUID authorizer = null;
        if (businessAccount.isRequiresAuthorization()) {
            List<AccountMember> authorizers = treasury.getAuthorizers(businessAccountId);
            boolean isAuth = authorizers.stream()
                    .anyMatch(a -> a.getMemberUuid().equals(playerUuid));
            if (!isAuth) {
                throw new NoPermissionException(KEY_NOT_AUTHORIZER);
            }
            authorizer = playerUuid;
        }

        TransferRequest req = new TransferRequest(
                businessAccountId,
                personal.getAccountId(),
                amount,
                "Business withdrawal",
                playerUuid,
                authorizer,
                "BusinessPlugin",
                null
        );

        return treasury.transfer(req);
    }

    @Override
    public BigDecimal getAggregateBalance(Integer firmId) {
        List<Integer> accountIds = firmAccounts.listAccountsByFirm(firmId).stream()
                .map(FirmAccount::getAccountId)
                .toList();
        // A firm with no live accounts (disbanded, or a corrupt/partial-heal state) has a
        // total balance of zero. Returning zero rather than throwing keeps read paths
        // (/firm info on a defunct firm, the disband prompt, the public BusinessApi) from
        // crashing; deposit/withdraw still surface the no-account condition via
        // resolveAccountId's no-account NotFoundException.
        // One batch balance read instead of one getBalanceByAccountId per account (ADT-36).
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal balance : treasury.getBalancesByIds(accountIds).values()) {
            total = total.add(balance);
        }
        return total;
    }

    @Override
    public String getFormattedAggregateBalance(Integer firmId) {
        return treasury.formatAmount(getAggregateBalance(firmId));
    }

    @Override
    public Page<FirmBalanceEntry> getFirmBalanceTop(int page, int pageSize) {
        if (page < 1) page = 1;
        if (pageSize < 1) pageSize = 10;

        List<Firm> activeFirms = firms.listAllActiveFirms();

        // Pull every live firm→account link once and batch-read all balances in a
        // single Treasury round-trip, then fold them into a per-firm total in
        // memory. Summing per firm would be N+1 IPC calls; this is one call.
        List<FirmAccount> links = firmAccounts.listActiveAccountLinks();
        List<Integer> accountIds = links.stream().map(FirmAccount::getAccountId).toList();
        Map<Integer, BigDecimal> balances = treasury.getBalancesByIds(accountIds);

        Map<Integer, BigDecimal> totals = new HashMap<>();
        for (FirmAccount link : links) {
            BigDecimal bal = balances.getOrDefault(link.getAccountId(), BigDecimal.ZERO);
            totals.merge(link.getFirmId(), bal, BigDecimal::add);
        }

        // Every active firm appears, even one whose accounts net to zero (or that
        // somehow has no live account), ranked highest-balance first with the name
        // as a stable tiebreak.
        List<FirmBalanceEntry> ranked = activeFirms.stream()
                .map(f -> new FirmBalanceEntry(f.getFirmId(), f.getDisplayName(),
                        totals.getOrDefault(f.getFirmId(), BigDecimal.ZERO)))
                .sorted(Comparator.comparing(FirmBalanceEntry::balance).reversed()
                        .thenComparing(FirmBalanceEntry::displayName,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        int totalCount = ranked.size();
        int offset = (page - 1) * pageSize;
        if (offset >= totalCount) {
            return new Page<>(List.of(), totalCount, offset, pageSize);
        }
        int end = Math.min(offset + pageSize, totalCount);
        return new Page<>(List.copyOf(ranked.subList(offset, end)), totalCount, offset, pageSize);
    }

    @Override
    public String formatAmount(BigDecimal amount) {
        return treasury.formatAmount(amount);
    }

    @Override
    public Page<TransactionEntry> getAggregateTransactions(Integer firmId, int page, int pageSize) {
        if (page < 1) page = 1;
        if (pageSize < 1) pageSize = 10;

        List<Integer> accountIds = firmAccounts.listAccountsByFirm(firmId).stream()
                .map(FirmAccount::getAccountId)
                .toList();

        // Treasury-side merged paged query (ADT-36): one round-trip with a correct
        // spanning totalCount, replacing the old fetch-page*pageSize-from-every-
        // account, concat, in-memory-sort, sublist (which also reported only the
        // fetched window as the total). Empty account list yields an empty page.
        int offset = (page - 1) * pageSize;
        return treasury.getTransactionHistory(accountIds, offset, pageSize);
    }

    private int resolveAccountId(Integer firmId) {
        Firm firm = firms.getFirmById(firmId); // ADT-99: direct by-id, no String round-trip
        if (firm == null) {
            throw new NotFoundException("business.firm.not-found", "firm", firmId);
        }

        // A default is only usable if the firm still owns that account. Archiving an
        // account soft-removes its firm_accounts link, so isFirmAccount also rejects a
        // default that has gone stale (points at an archived/removed account) — the
        // exact condition that made proprietors "unable to withdraw" (PAR-45/PAR-62).
        Integer current = firm.getDefaultAccountId();
        if (current != null && firmAccounts.isFirmAccount(firmId, current)) {
            return current;
        }

        // Unset or stale default: re-point to a surviving account so the displayed
        // default and the resolution path agree, then persist the heal. Any account
        // returned by getAnyAccountId is a live (non-removed) firm account.
        Integer survivor = firmAccounts.getAnyAccountId(firmId);
        if (survivor == null) {
            // Distinct semantic exception with its own key so deposit/pay-in report the
            // real cause ("no usable account") instead of misreporting insufficient
            // personal funds (behaviour/0001).
            throw new NotFoundException(KEY_NO_ACCOUNT);
        }

        firms.updateDefaultAccount(firmId, survivor);
        return survivor;
    }

    private void validateAccountBelongsToFirm(Integer firmId, Integer accountId) {
        if (!firmAccounts.isFirmAccount(firmId, accountId)) {
            throw new BadCommandException(KEY_FOREIGN_ACCOUNT);
        }
    }

    @Override
    public BigDecimal getAccountBalance(Integer firmId, Integer accountId) {
        validateAccountBelongsToFirm(firmId, accountId);
        return treasury.getBalanceByAccountId(accountId);
    }

    @Override
    public String getFormattedAccountBalance(Integer firmId, Integer accountId) {
        BigDecimal balance = getAccountBalance(firmId, accountId);
        return treasury.formatAmount(balance);
    }

    @Override
    public Page<TransactionEntry> getAccountTransactions(Integer firmId, Integer accountId, int page, int pageSize) {
        validateAccountBelongsToFirm(firmId, accountId);
        if (page < 1) page = 1;
        if (pageSize < 1) pageSize = 10;
        int offset = (page - 1) * pageSize;
        return treasury.getTransactionHistory(accountId, offset, pageSize);
    }

    @Override
    public long depositToAccount(Integer firmId, Integer accountId, UUID playerUuid, BigDecimal amount) {
        validateAccountBelongsToFirm(firmId, accountId);

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadCommandException(KEY_INVALID_AMOUNT);
        }

        Account businessAccount = treasury.getAccountById(accountId);
        if (businessAccount != null && businessAccount.isArchived()) {
            throw new ConflictException(KEY_ARCHIVED);
        }

        Account personal = treasury.resolveOrCreatePersonal(playerUuid);

        if (!treasury.hasFunds(personal.getAccountId(), amount)) {
            throw new ExceedsLimitException(KEY_INSUFFICIENT_PERSONAL);
        }

        if (!treasury.canAccessAccount(playerUuid, accountId)) {
            throw new NoPermissionException(KEY_NO_PERMISSION);
        }

        TransferRequest req = new TransferRequest(
                personal.getAccountId(),
                accountId,
                amount,
                "Business deposit",
                playerUuid,
                null,
                "BusinessPlugin",
                null
        );

        return treasury.transfer(req);
    }

    @Override
    public long withdrawFromAccount(Integer firmId, Integer accountId, UUID playerUuid, BigDecimal amount) {
        validateAccountBelongsToFirm(firmId, accountId);

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadCommandException(KEY_INVALID_AMOUNT);
        }

        Account businessAccount = treasury.getAccountById(accountId);
        Account personal = treasury.resolveOrCreatePersonal(playerUuid);

        if (!treasury.canAccessAccount(playerUuid, accountId)) {
            throw new NoPermissionException(KEY_NO_PERMISSION);
        }

        if (!treasury.hasFunds(accountId, amount)) {
            throw new ExceedsLimitException(KEY_INSUFFICIENT_BUSINESS);
        }

        UUID authorizer = null;
        if (businessAccount.isRequiresAuthorization()) {
            List<AccountMember> authorizers = treasury.getAuthorizers(accountId);
            boolean isAuth = authorizers.stream()
                    .anyMatch(a -> a.getMemberUuid().equals(playerUuid));
            if (!isAuth) {
                throw new NoPermissionException(KEY_NOT_AUTHORIZER);
            }
            authorizer = playerUuid;
        }

        TransferRequest req = new TransferRequest(
                accountId,
                personal.getAccountId(),
                amount,
                "Business withdrawal",
                playerUuid,
                authorizer,
                "BusinessPlugin",
                null
        );

        return treasury.transfer(req);
    }

    // ---- PAYMENTS ---------------------------------------------------------------

    @Override
    public long payIntoFirm(Integer firmId, UUID payerUuid, BigDecimal amount) {
        int destAccountId = resolveAccountId(firmId);
        return payIn(destAccountId, payerUuid, amount,
                "Business payment: " + playerName(payerUuid) + " -> " + firmName(firmId));
    }

    @Override
    public long payIntoAccount(Integer firmId, Integer accountId, UUID payerUuid, BigDecimal amount) {
        validateAccountBelongsToFirm(firmId, accountId);
        return payIn(accountId, payerUuid, amount,
                "Business payment: " + playerName(payerUuid) + " -> " + firmName(firmId) + " #" + accountId);
    }

    @Override
    public long payPlayer(Integer firmId, UUID targetPlayerUuid, UUID actorUuid, BigDecimal amount) {
        int sourceAccountId = resolveAccountId(firmId);
        Account target = treasury.resolveOrCreatePersonal(targetPlayerUuid);
        return payOut(sourceAccountId, target.getAccountId(), actorUuid, amount,
                "Business payment: " + firmName(firmId) + " -> " + playerName(targetPlayerUuid));
    }

    @Override
    public long payPlayerFromAccount(Integer firmId, Integer accountId, UUID targetPlayerUuid, UUID actorUuid, BigDecimal amount) {
        validateAccountBelongsToFirm(firmId, accountId);
        Account target = treasury.resolveOrCreatePersonal(targetPlayerUuid);
        return payOut(accountId, target.getAccountId(), actorUuid, amount,
                "Business payment: " + firmName(firmId) + " #" + accountId + " -> " + playerName(targetPlayerUuid));
    }

    @Override
    public long payFirm(Integer sourceFirmId, Integer targetFirmId, UUID actorUuid, BigDecimal amount) {
        return payFirm(sourceFirmId, targetFirmId, actorUuid, amount, null);
    }

    @Override
    public long payFirm(Integer sourceFirmId, Integer targetFirmId, UUID actorUuid, BigDecimal amount, String memo) {
        int sourceAccountId = resolveAccountId(sourceFirmId);
        int destAccountId = resolveAccountId(targetFirmId);
        if (sourceAccountId == destAccountId) {
            throw new BadCommandException(KEY_SAME_FIRM);
        }
        String base = "Business payment: " + firmName(sourceFirmId) + " -> " + firmName(targetFirmId);
        return payOut(sourceAccountId, destAccountId, actorUuid, amount, reasonWithMemo(base, memo));
    }

    @Override
    public long payFirmFromAccount(Integer sourceFirmId, Integer sourceAccountId, Integer targetFirmId, UUID actorUuid, BigDecimal amount) {
        validateAccountBelongsToFirm(sourceFirmId, sourceAccountId);
        int destAccountId = resolveAccountId(targetFirmId);
        if (sourceAccountId == destAccountId) {
            throw new BadCommandException(KEY_SAME_FIRM);
        }
        return payOut(sourceAccountId, destAccountId, actorUuid, amount,
                "Business payment: " + firmName(sourceFirmId) + " #" + sourceAccountId + " -> " + firmName(targetFirmId));
    }

    /**
     * Inbound payment into a firm account. No access check on the payer — paying
     * money in is harmless — but the payer must have funds and the destination
     * must not be archived.
     */
    private long payIn(int destAccountId, UUID payerUuid, BigDecimal amount, String description) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadCommandException(KEY_INVALID_AMOUNT);
        }

        Account dest = treasury.getAccountById(destAccountId);
        if (dest != null && dest.isArchived()) {
            throw new ConflictException(KEY_ARCHIVED);
        }

        Account personal = treasury.resolveOrCreatePersonal(payerUuid);
        if (!treasury.hasFunds(personal.getAccountId(), amount)) {
            throw new ExceedsLimitException(KEY_INSUFFICIENT_PERSONAL);
        }

        TransferRequest req = new TransferRequest(
                personal.getAccountId(),
                destAccountId,
                amount,
                description,
                payerUuid,
                null,
                "BusinessPlugin",
                null
        );

        return treasury.transfer(req);
    }

    /**
     * Outbound payment from a firm account. Mirrors {@link #withdrawFromAccount}'s
     * Treasury-level checks: the actor must be able to access the source account,
     * the source must have funds, and if the source requires authorization the
     * actor must be one of its authorizers. (Firm-finance permission is checked
     * by the command layer before reaching here.)
     */
    private long payOut(int sourceAccountId, int destAccountId, UUID actorUuid, BigDecimal amount, String description) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadCommandException(KEY_INVALID_AMOUNT);
        }

        Account source = treasury.getAccountById(sourceAccountId);

        if (!treasury.canAccessAccount(actorUuid, sourceAccountId)) {
            throw new NoPermissionException(KEY_NO_PERMISSION);
        }

        if (!treasury.hasFunds(sourceAccountId, amount)) {
            throw new ExceedsLimitException(KEY_INSUFFICIENT_BUSINESS);
        }

        UUID authorizer = null;
        if (source.isRequiresAuthorization()) {
            List<AccountMember> authorizers = treasury.getAuthorizers(sourceAccountId);
            boolean isAuth = authorizers.stream()
                    .anyMatch(a -> a.getMemberUuid().equals(actorUuid));
            if (!isAuth) {
                throw new NoPermissionException(KEY_NOT_AUTHORIZER);
            }
            authorizer = actorUuid;
        }

        TransferRequest req = new TransferRequest(
                sourceAccountId,
                destAccountId,
                amount,
                description,
                actorUuid,
                authorizer,
                "BusinessPlugin",
                null
        );

        return treasury.transfer(req);
    }

    private String firmName(Integer firmId) {
        Firm firm = firms.getFirmById(firmId); // ADT-99: direct by-id, no String round-trip
        return firm != null ? firm.getDisplayName() : ("firm#" + firmId);
    }

    private String playerName(UUID uuid) {
        return players.findByUuid(uuid).map(FirmPlayer::getCurrentName).orElse(uuid.toString());
    }
}
