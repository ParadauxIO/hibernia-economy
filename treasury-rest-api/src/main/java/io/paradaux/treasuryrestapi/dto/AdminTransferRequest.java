package io.paradaux.treasuryrestapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Admin transfer between two arbitrary accounts. Amount is a decimal string. */
public record AdminTransferRequest(
        @NotNull Long fromAccountId,
        @NotNull Long toAccountId,
        @NotBlank String amount,
        @Size(max = 255) String memo) {}
