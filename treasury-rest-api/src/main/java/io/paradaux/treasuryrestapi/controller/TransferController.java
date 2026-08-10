package io.paradaux.treasuryrestapi.controller;

import io.paradaux.treasuryrestapi.dto.FirmTransferRequest;
import io.paradaux.treasuryrestapi.dto.PlayerTransferRequest;
import io.paradaux.treasuryrestapi.dto.TransferRequest;
import io.paradaux.treasuryrestapi.dto.TransferResponse;
import io.paradaux.treasuryrestapi.ratelimit.RateLimit;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import io.paradaux.treasuryrestapi.service.IdempotencyReplay;
import io.paradaux.treasuryrestapi.service.TransferService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private static final Logger log = LoggerFactory.getLogger(TransferController.class);

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * POST /api/v1/transfers
     * Creates a transfer from the token's account to another account.
     * The source account is always the {@code acc} claim — callers cannot specify an arbitrary source.
     */
    @PostMapping
    @RateLimit(personalPerMinute = 30, businessPerMinute = 120, failClosed = true)
    public ResponseEntity<TransferResponse> transfer(
            @AuthenticationPrincipal VerifiedToken verified,
            @Valid @RequestBody TransferRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        log.info("POST /transfers requested by keyId={} ownerUuid={} | fromAccount={} toAccount={} amount={} idempotencyKey={}",
                verified.keyId(), verified.ownerUuid(),
                request.fromAccountId() != null ? request.fromAccountId() : verified.accountId(),
                request.toAccountId(), request.amount(),
                idempotencyKey != null ? "present" : "absent");

        TransferResponse response = IdempotencyReplay.withReplay(
                () -> transferService.transfer(verified, request, idempotencyKey));

        log.info("Transfer completed: txnId={} fromAccount={} toAccount={} amount={}",
                response.txnId(), response.fromAccountId(), response.toAccountId(), response.amount());

        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/v1/transfers/to-firm
     * Transfers to the named firm's default account, resolving the destination automatically.
     * Accepts the same {@code Idempotency-Key} header as the standard transfer endpoint.
     */
    @PostMapping("/to-firm")
    @RateLimit(personalPerMinute = 30, businessPerMinute = 120, failClosed = true)
    public ResponseEntity<TransferResponse> transferToFirm(
            @AuthenticationPrincipal VerifiedToken verified,
            @Valid @RequestBody FirmTransferRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        log.info("POST /transfers/to-firm requested by keyId={} ownerUuid={} | fromAccount={} toFirm={} amount={} idempotencyKey={}",
                verified.keyId(), verified.ownerUuid(),
                request.fromAccountId() != null ? request.fromAccountId() : verified.accountId(),
                request.toFirm(), request.amount(),
                idempotencyKey != null ? "present" : "absent");

        TransferResponse response = IdempotencyReplay.withReplay(
                () -> transferService.transferToFirm(verified, request, idempotencyKey));

        log.info("Firm transfer completed: txnId={} fromAccount={} toAccount={} amount={}",
                response.txnId(), response.fromAccountId(), response.toAccountId(), response.amount());

        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/v1/transfers/to-player
     * Transfers to a player identified by UUID or in-game name, resolving the
     * destination to their PERSONAL account automatically. Provide exactly one of
     * {@code toPlayerUuid} / {@code toPlayerName}. Same {@code Idempotency-Key}
     * semantics as the standard transfer endpoint.
     */
    @PostMapping("/to-player")
    @RateLimit(personalPerMinute = 30, businessPerMinute = 120, failClosed = true)
    public ResponseEntity<TransferResponse> transferToPlayer(
            @AuthenticationPrincipal VerifiedToken verified,
            @Valid @RequestBody PlayerTransferRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        log.info("POST /transfers/to-player requested by keyId={} ownerUuid={} | fromAccount={} toPlayerUuid={} toPlayerName={} amount={} idempotencyKey={}",
                verified.keyId(), verified.ownerUuid(),
                request.fromAccountId() != null ? request.fromAccountId() : verified.accountId(),
                request.toPlayerUuid(), request.toPlayerName(), request.amount(),
                idempotencyKey != null ? "present" : "absent");

        TransferResponse response = IdempotencyReplay.withReplay(
                () -> transferService.transferToPlayer(verified, request, idempotencyKey));

        log.info("Player transfer completed: txnId={} fromAccount={} toAccount={} amount={}",
                response.txnId(), response.fromAccountId(), response.toAccountId(), response.amount());

        return ResponseEntity.ok(response);
    }
}
