package io.paradaux.treasuryrestapi.service;

import io.paradaux.treasuryrestapi.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Shared validation for the user-settable firm fields, so the column-width limits
 * and the empty-to-null normalisation live in one place rather than being
 * copy-pasted between {@link FirmService} (owner self-service) and
 * {@link AdminFirmService} (SERVICE-scoped admin) — ADT-120.
 */
final class FirmFieldLimits {

    static final int MAX_DISCORD_URL_LENGTH = 255; // firm.discord_url
    static final int MAX_HQ_REGION_LENGTH = 64;    // firm.hq_region

    private FirmFieldLimits() {}

    /** Reject over-long discord_url / hq_region up front so they 400 rather than hitting a driver-level 500. */
    static void validate(String discordUrl, String hqRegion) {
        if (discordUrl != null && discordUrl.strip().length() > MAX_DISCORD_URL_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BODY",
                    "Field 'discordUrl' must be at most " + MAX_DISCORD_URL_LENGTH + " characters.");
        }
        if (hqRegion != null && hqRegion.strip().length() > MAX_HQ_REGION_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BODY",
                    "Field 'hqRegion' must be at most " + MAX_HQ_REGION_LENGTH + " characters.");
        }
    }

    /** Strip a non-null value, mapping blank to null. Callers guard the null case before calling. */
    static String emptyToNull(String value) {
        return value.isBlank() ? null : value.strip();
    }
}
