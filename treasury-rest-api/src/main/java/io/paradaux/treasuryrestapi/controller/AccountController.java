package io.paradaux.treasuryrestapi.controller;

import io.paradaux.treasuryrestapi.dto.AccountBalanceResponse;
import io.paradaux.treasuryrestapi.dto.AccountByPlayerResponse;
import io.paradaux.treasuryrestapi.dto.TransactionFeedResponse;
import io.paradaux.treasuryrestapi.dto.TransactionsResponse;
import io.paradaux.treasuryrestapi.ratelimit.RateLimit;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import io.paradaux.treasuryrestapi.service.AccountService;
import io.paradaux.treasuryrestapi.service.TransactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    private final TransactionService transactionService;
    private final AccountService accountService;

    public AccountController(TransactionService transactionService,
                             AccountService accountService) {
        this.transactionService = transactionService;
        this.accountService = accountService;
    }

    /**
     * GET /api/v1/accounts/{accountId}/balance
     * Returns the current balance of an account.
     * Any authenticated caller may query any account.
     */
    @GetMapping("/{accountId}/balance")
    @RateLimit(personalPerMinute = 120, businessPerMinute = 600)
    public ResponseEntity<AccountBalanceResponse> getBalance(
            @AuthenticationPrincipal VerifiedToken verified,
            @PathVariable long accountId) {

        log.info("GET /accounts/{}/balance requested by keyId={}", accountId, verified.keyId());

        return ResponseEntity.ok(accountService.getBalance(accountId));
    }

    /**
     * GET /api/v1/accounts/by-player?uuid={uuid}|name={name}
     * Resolves a player to their non-archived PERSONAL account id. Exactly one
     * of {@code uuid} or {@code name} must be supplied. Names are matched
     * case-insensitively via the {@code economy_players} IGN cache, so a player
     * must have been seen on the server at least once for name lookup to work
     * (UUID lookup has no such constraint).
     */
    @GetMapping("/by-player")
    @RateLimit(personalPerMinute = 60, businessPerMinute = 300)
    public ResponseEntity<AccountByPlayerResponse> getAccountByPlayer(
            @AuthenticationPrincipal VerifiedToken verified,
            @RequestParam(required = false) String uuid,
            @RequestParam(required = false) String name) {

        AccountByPlayerResponse response = accountService.resolvePlayerAccount(uuid, name);

        log.info("GET /accounts/by-player resolved accountId={} (player={}) for keyId={}",
                response.accountId(), response.playerUuid(), verified.keyId());

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/accounts/{accountId}/transactions
     * Returns paginated transaction history for an account.
     * The caller must own the account or be a member of it.
     */
    @GetMapping("/{accountId}/transactions")
    @RateLimit(personalPerMinute = 60, businessPerMinute = 300)
    public ResponseEntity<TransactionsResponse> getTransactions(
            @AuthenticationPrincipal VerifiedToken verified,
            @PathVariable long accountId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit) {

        log.info("GET /accounts/{}/transactions requested by keyId={} ownerUuid={} | page={} limit={}",
                accountId, verified.keyId(), verified.ownerUuid(), page, limit);

        TransactionsResponse response = transactionService.getTransactions(verified, accountId, page, limit);

        log.info("Returning {} transactions for accountId={} (page {}/{}, totalItems={})",
                response.items().size(), accountId, response.page(), response.totalPages(), response.totalItems());

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/accounts/{accountId}/transactions/feed?since={txnId}&limit={n}
     * Forward cursor "pull" feed of settled transactions with {@code txn_id > since},
     * oldest-first (default 100, max 1000). The caller must own the account or be a
     * member of it (same rules as the paginated endpoint). Intended for polling
     * consumers that track their last-seen {@code txn_id}; the response carries
     * {@code nextCursor} + {@code hasMore} for the next call.
     */
    @GetMapping("/{accountId}/transactions/feed")
    @RateLimit(personalPerMinute = 120, businessPerMinute = 600)
    public ResponseEntity<TransactionFeedResponse> getTransactionFeed(
            @AuthenticationPrincipal VerifiedToken verified,
            @PathVariable long accountId,
            @RequestParam(defaultValue = "0") long since,
            @RequestParam(defaultValue = "0") int limit) {

        log.info("GET /accounts/{}/transactions/feed requested by keyId={} | since={} limit={}",
                accountId, verified.keyId(), since, limit);

        TransactionFeedResponse response = transactionService.getTransactionFeed(verified, accountId, since, limit);
        return ResponseEntity.ok(response);
    }
}
