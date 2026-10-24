package io.paradaux.treasuryrestapi.controller;

import io.paradaux.treasuryrestapi.dto.AdminTransferRequest;
import io.paradaux.treasuryrestapi.dto.TransferResponse;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import io.paradaux.treasuryrestapi.service.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for treasury-rest-api/behaviour/0001: the SERVICE-scoped admin
 * transfer endpoint must survive the check-then-insert idempotency race the same way
 * the token path does. {@code adminTransfer} derives the same UNIQUE
 * {@code client_dedup_key}, so two concurrent same-key admin transfers can both pass
 * the pre-check; the loser's insert then trips the constraint as a
 * {@link DuplicateKeyException}. Before the fix the admin controller called the
 * service directly with no retry, so that surfaced as a 500 instead of replaying the
 * winner's committed result. The controller must now retry once (via the shared
 * {@code IdempotencyReplay} helper) and return the replayed response.
 */
class AdminTransferControllerIdempotencyTest {

    private static final UUID OWNER = UUID.fromString("99999999-9999-9999-9999-999999999999");

    private VerifiedToken serviceToken() {
        return new VerifiedToken(1L, OWNER, "SERVICE", null, null);
    }

    private AdminTransferRequest request() {
        return new AdminTransferRequest(10L, 20L, "100.00", "memo");
    }

    private TransferResponse response() {
        return new TransferResponse(777L, 10L, 20L, "100.00", "memo", Instant.EPOCH);
    }

    @Test
    void concurrentSameKeyRace_replaysOnce_insteadOf500() {
        TransferService service = mock(TransferService.class);
        // First call loses the insert race; the retry finds the committed row and replays.
        when(service.adminTransfer(any(), eq(10L), eq(20L), eq("100.00"), eq("memo"), eq("idem-key")))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key 'client_dedup_key'"))
                .thenReturn(response());

        AdminTransferController controller = new AdminTransferController(service);

        ResponseEntity<TransferResponse> result =
                controller.transfer(serviceToken(), request(), "idem-key");

        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().txnId()).isEqualTo(777L);
        // Exactly one retry: the original attempt plus one replay.
        verify(service, times(2))
                .adminTransfer(any(), eq(10L), eq(20L), eq("100.00"), eq("memo"), eq("idem-key"));
    }

    @Test
    void happyPath_callsServiceExactlyOnce() {
        TransferService service = mock(TransferService.class);
        when(service.adminTransfer(any(), any(), any(), any(), any(), any()))
                .thenReturn(response());

        AdminTransferController controller = new AdminTransferController(service);

        ResponseEntity<TransferResponse> result =
                controller.transfer(serviceToken(), request(), "idem-key");

        assertThat(result.getBody().txnId()).isEqualTo(777L);
        verify(service, times(1)).adminTransfer(any(), any(), any(), any(), any(), any());
    }
}
