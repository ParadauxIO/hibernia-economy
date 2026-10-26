package io.paradaux.treasuryapi.commands;

import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasuryapi.model.economy.ApiKey;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Shared presentation of an {@link ApiKey}'s status/expiry for the {@code list}
 * command loops. Collapses the {@code EXP_FMT} formatter and the
 * revoked→status/expiry derivation that was duplicated across
 * {@link PersonalKeyHandler} and {@link BusinessKeyHandler}
 * (treasury-api-plugin/structure/0002), and moves the hard-coded {@code Active} /
 * {@code Revoked} / {@code —} labels behind i18n keys
 * (treasury-api-plugin/plugin-architecture/0004) so they can be translated/re-worded.
 */
final class ApiKeyView {

    private static final DateTimeFormatter EXP_FMT =
            DateTimeFormatter.ofPattern("MM/dd/yy").withZone(ZoneId.systemDefault());

    private ApiKeyView() {
    }

    /** The localised status label for a key: revoked → {@code status.revoked}, else {@code status.active}. */
    static String status(ApiKey key, Message message) {
        return message.format(key.isRevoked()
                ? "treasuryapi.status.revoked"
                : "treasuryapi.status.active");
    }

    /**
     * The expiry cell for a key: the localised empty-expiry marker when revoked (no
     * meaningful expiry to show), otherwise the formatted {@code expiresAt} date.
     */
    static String expiry(ApiKey key, Message message) {
        return key.isRevoked()
                ? message.format("treasuryapi.status.expiry-none")
                : EXP_FMT.format(key.getExpiresAt());
    }
}
