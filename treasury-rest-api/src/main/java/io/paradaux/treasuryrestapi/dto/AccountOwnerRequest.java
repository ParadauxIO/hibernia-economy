package io.paradaux.treasuryrestapi.dto;

import jakarta.validation.constraints.NotBlank;

/** Admin account owner change. {@code owner} is a UUID or a player name. */
public record AccountOwnerRequest(@NotBlank String owner) {}
