package io.paradaux.treasuryrestapi.security;

import io.paradaux.treasuryrestapi.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * The player behind an admin call (PAR-542). The economy explorer authenticates
 * to the admin API with one shared SERVICE key, so on its own the API only knows
 * "the explorer" did something. The explorer names the signed-in player who
 * clicked in the {@value #HEADER} header.
 *
 * <p>The header is trusted only alongside a SERVICE token: the explorer has
 * already authenticated and authorised the player, and only a SERVICE key holder
 * can make admin calls at all. On any other token it is ignored. A SERVICE call
 * with a malformed value is rejected with 400, so a broken client fails loudly
 * instead of being silently attributed to nobody.
 */
public final class ActingPlayer {

    /** Request header carrying the acting player's UUID. */
    public static final String HEADER = "X-Acting-Player";

    /** Request attribute holding the validated {@link UUID}, set by {@link ActingPlayerInterceptor}. */
    public static final String ATTRIBUTE = "io.paradaux.treasuryrestapi.security.ActingPlayer";

    /** Canonical 8-4-4-4-12 hex form only; {@link UUID#fromString} alone accepts shortened groups. */
    private static final Pattern CANONICAL =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final UUID NIL = new UUID(0L, 0L);

    private ActingPlayer() {}

    /**
     * The acting player for a request made with {@code verified}, from the raw
     * header value.
     *
     * @return empty when the header is absent or blank, or the token is not a SERVICE key
     * @throws ApiException 400 {@code INVALID_ACTING_PLAYER} for a SERVICE call whose header is not a canonical, non-nil UUID
     */
    public static Optional<UUID> resolve(VerifiedToken verified, String header) {
        if (verified == null || !"SERVICE".equals(verified.keyType())) return Optional.empty();
        if (header == null || header.isBlank()) return Optional.empty();
        String value = header.trim();
        if (!CANONICAL.matcher(value).matches()) {
            throw invalid();
        }
        UUID uuid = UUID.fromString(value);
        if (NIL.equals(uuid)) throw invalid();
        return Optional.of(uuid);
    }

    /** The value {@link ActingPlayerInterceptor} validated for this request, if any. */
    public static Optional<UUID> of(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof UUID uuid ? Optional.of(uuid) : Optional.empty();
    }

    private static ApiException invalid() {
        return new ApiException(
                HttpStatus.BAD_REQUEST, "INVALID_ACTING_PLAYER", "Header '" + HEADER + "' must be a player UUID.");
    }
}
