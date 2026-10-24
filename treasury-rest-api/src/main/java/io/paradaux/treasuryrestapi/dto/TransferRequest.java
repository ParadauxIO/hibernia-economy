package io.paradaux.treasuryrestapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /transfers.
 * <ul>
 *   <li>{@code fromAccountId} — required for BUSINESS keys (must be an account owned by the firm);
 *       ignored for PERSONAL and GOVERNMENT keys, which always debit the {@code acc} JWT claim.</li>
 *   <li>{@code amount} — decimal string to avoid IEEE 754 precision loss.</li>
 *   <li>{@code memo} — optional, max 255 chars.</li>
 * </ul>
 *
 * <p>Bean-validation here is a declarative first line of defence; the
 * authoritative amount/account checks still live in {@code TransferService}.
 */
public record TransferRequest(
        Long fromAccountId,
        @NotNull Long toAccountId,
        @NotBlank String amount,
        @Size(max = 255) String memo) {}
