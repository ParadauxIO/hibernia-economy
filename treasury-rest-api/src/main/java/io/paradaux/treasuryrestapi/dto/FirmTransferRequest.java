package io.paradaux.treasuryrestapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /transfers/to-firm.
 * Resolves the destination to the named firm's default account automatically.
 * {@code fromAccountId} follows the same rules as {@link TransferRequest}:
 * required for BUSINESS keys, ignored for PERSONAL/GOVERNMENT (uses the {@code acc} JWT claim).
 */
public record FirmTransferRequest(
        Long fromAccountId,
        @NotBlank String toFirm,
        @NotBlank String amount,
        @Size(max = 255) String memo) {}
