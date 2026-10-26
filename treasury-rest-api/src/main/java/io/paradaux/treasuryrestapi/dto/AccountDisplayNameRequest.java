package io.paradaux.treasuryrestapi.dto;

import jakarta.validation.constraints.NotBlank;

/** Admin account rename. */
public record AccountDisplayNameRequest(@NotBlank String displayName) {}
