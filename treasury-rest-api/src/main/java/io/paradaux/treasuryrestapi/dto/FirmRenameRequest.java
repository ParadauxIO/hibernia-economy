package io.paradaux.treasuryrestapi.dto;

import jakarta.validation.constraints.NotBlank;

/** Admin firm-rename request body. {@code newName} follows the in-game name rules. */
public record FirmRenameRequest(@NotBlank String newName) {}
