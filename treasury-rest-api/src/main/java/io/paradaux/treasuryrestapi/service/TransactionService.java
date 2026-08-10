package io.paradaux.treasuryrestapi.service;

import io.paradaux.treasuryrestapi.dto.TransactionFeedResponse;
import io.paradaux.treasuryrestapi.dto.TransactionItem;
import io.paradaux.treasuryrestapi.dto.TransactionsResponse;
import io.paradaux.treasuryrestapi.exception.ApiException;
import io.paradaux.treasuryrestapi.mapper.AccountMapper;
import io.paradaux.treasuryrestapi.mapper.FirmMapper;
import io.paradaux.treasuryrestapi.mapper.LedgerMapper;
import io.paradaux.treasuryrestapi.mapper.MembershipMapper;
import io.paradaux.treasuryrestapi.model.Account;
import io.paradaux.treasuryrestapi.model.TransactionRow;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import io.paradaux.treasuryrestapi.util.Pagination;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.util.List;

@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    /** Hard cap on a single feed page. */
    private static final int FEED_MAX_LIMIT = 1000;
    private static final int FEED_DEFAULT_LIMIT = 100;

    private final AccountMapper accountMapper;
    private final LedgerMapper ledgerMapper;
    private final MembershipMapper membershipMapper;
    private final FirmMapper firmMapper;

    /**
     * Seconds a transaction must have been settled before it enters the cursor
     * feed — see {@link LedgerMapper#findTransactionsSince}. Keeps a
     * {@code txn_id > cursor} poll skip-free under concurrent inserts.
     */
    private final int feedSettlementLagSeconds;

    public TransactionService(AccountMapper accountMapper,
                              LedgerMapper ledgerMapper,
                              MembershipMapper membershipMapper,
                              FirmMapper firmMapper,
                              @Value("${treasury.feed.settlement-lag-seconds:3}") int feedSettlementLagSeconds) {
        this.accountMapper = accountMapper;
        this.ledgerMapper = ledgerMapper;
        this.membershipMapper = membershipMapper;
        this.firmMapper = firmMapper;
        this.feedSettlementLagSeconds = feedSettlementLagSeconds;
    }

    /**
     * Returns paginated transaction history for the given account.
     * The caller must be either the account owner or a member of the account.
     *
     * @param page  1-based page number
     * @param limit items per page (1–100)
     */
    public TransactionsResponse getTransactions(VerifiedToken verified,
                                                long accountId,
                                                int page,
                                                int limit) {
        log.debug("Fetching transactions for accountId={} | callerKeyId={} ownerUuid={} page={} limit={}",
                accountId, verified.keyId(), verified.ownerUuid(), page, limit);

        // Step 2: accountId is a positive integer — enforced by controller path variable type
        // Step 3: validate pagination params
        if (page < 1) {
            log.warn("Invalid page parameter: page={} for accountId={}", page, accountId);
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAM",
                    "Query parameter 'page' must be >= 1.");
        }
        if (limit < 1 || limit > 100) {
            log.warn("Invalid limit parameter: limit={} for accountId={}", limit, accountId);
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAM",
                    "Query parameter 'limit' must be between 1 and 100.");
        }
        // Compute the offset in long so a large page can't overflow int into a
        // negative value — MariaDB rejects a negative OFFSET, which would surface
        // as a 500. Reject any page whose offset can't be served as a clean 400.
        int offset = Pagination.offset(page, limit);

        // Steps 4 & 5: account must exist and the caller must be authorised
        requireTransactionAccess(verified, accountId);

        // Steps 6 & 7: query history and total count
        List<TransactionRow> rows = ledgerMapper.findTransactionsByAccount(accountId, limit, offset);
        long totalItems = ledgerMapper.countTransactionsByAccount(accountId);
        int totalPages = (int) Math.ceil((double) totalItems / limit);

        List<TransactionItem> items = rows.stream()
                .map(this::toItem)
                .toList();

        log.info("Returning {} transactions for accountId={} (page {}/{}, totalItems={})",
                items.size(), accountId, page, totalPages, totalItems);

        return new TransactionsResponse(accountId, page, totalPages, totalItems, items);
    }

    /**
     * Forward cursor "pull" feed for an account: settled transactions with
     * {@code txn_id > since}, oldest-first, capped. Same access rules as
     * {@link #getTransactions}. Intended for polling consumers (player banks)
     * who track their last-seen {@code txn_id}.
     *
     * @param since the caller's cursor; 0 starts from the oldest settled txn
     * @param limit page size (1..{@value #FEED_MAX_LIMIT}); a non-positive value
     *              uses the default of {@value #FEED_DEFAULT_LIMIT}
     */
    public TransactionFeedResponse getTransactionFeed(VerifiedToken verified,
                                                      long accountId,
                                                      long since,
                                                      int limit) {
        if (since < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAM",
                    "Query parameter 'since' must be >= 0.");
        }
        if (limit <= 0) {
            limit = FEED_DEFAULT_LIMIT;
        } else if (limit > FEED_MAX_LIMIT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAM",
                    "Query parameter 'limit' must be between 1 and " + FEED_MAX_LIMIT + ".");
        }

        requireTransactionAccess(verified, accountId);

        List<TransactionRow> rows =
                ledgerMapper.findTransactionsSince(accountId, since, feedSettlementLagSeconds, limit);
        List<TransactionItem> items = rows.stream().map(this::toItem).toList();

        Long nextCursor = items.isEmpty() ? null : items.get(items.size() - 1).txnId();
        boolean hasMore = items.size() == limit;

        log.info("Feed: {} txns for accountId={} since={} (nextCursor={}, hasMore={})",
                items.size(), accountId, since, nextCursor, hasMore);

        return new TransactionFeedResponse(accountId, since, nextCursor, hasMore, items);
    }

    /**
     * Asserts the account exists and {@code verified} may read its transactions.
     * BUSINESS keys may read any account belonging to their firm; PERSONAL /
     * GOVERNMENT keys must own the account or be a member of it. Throws an
     * {@link ApiException} (404 / 403) otherwise.
     */
    private void requireTransactionAccess(VerifiedToken verified, long accountId) {
        // Account must exist (archived accounts are still queryable for history)
        Account account = accountMapper.findById(accountId);
        if (account == null) {
            log.warn("Account not found: accountId={}", accountId);
            throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found.");
        }

        if ("BUSINESS".equals(verified.keyType())) {
            if (firmMapper.isFirmAccount(verified.firmId(), accountId) == 0) {
                log.warn("Access denied: firmId={} does not own accountId={}", verified.firmId(), accountId);
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                        "Account does not belong to the firm associated with this API key.");
            }
            log.debug("Access granted for accountId={}: firmId={}", accountId, verified.firmId());
        } else {
            boolean isOwner = verified.accountId() != null && verified.accountId() == accountId;
            boolean isMember = !isOwner && membershipMapper.isMember(accountId, verified.ownerUuid()) > 0;
            if (!isOwner && !isMember) {
                log.warn("Access denied: ownerUuid={} is neither owner nor member of accountId={}",
                        verified.ownerUuid(), accountId);
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                        "You are not authorised to view transactions for this account.");
            }
            log.debug("Access granted for accountId={}: isOwner={} isMember={}", accountId, isOwner, isMember);
        }
    }

    private TransactionItem toItem(TransactionRow row) {
        return new TransactionItem(
                row.getPostingId(),
                row.getTxnId(),
                row.getAmount().toPlainString(),
                row.getMemo(),
                row.getMessage(),
                row.getSettlementTime().toInstant(ZoneOffset.UTC),
                row.getInitiatorUuidBin() != null ? row.getInitiatorUuidBin().toString() : null,
                row.getPluginSystem()
        );
    }
}
