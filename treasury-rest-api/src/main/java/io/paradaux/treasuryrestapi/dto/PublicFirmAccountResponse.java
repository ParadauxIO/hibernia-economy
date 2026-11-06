package io.paradaux.treasuryrestapi.dto;

/** An account currently linked to a firm, without private account settings. */
public record PublicFirmAccountResponse(long accountId, String displayName, boolean defaultAccount) {}
