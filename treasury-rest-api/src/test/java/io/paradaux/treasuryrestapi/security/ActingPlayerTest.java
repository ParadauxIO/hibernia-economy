package io.paradaux.treasuryrestapi.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.paradaux.treasuryrestapi.exception.ApiException;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

/** {@link ActingPlayer}: trusted only with a SERVICE token, and only as a canonical UUID (PAR-542). */
class ActingPlayerTest {

    private static final UUID OWNER = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final String ALEX = "0b5a1c2d-3e4f-4a5b-8c6d-7e8f9a0b1c2d";

    private static VerifiedToken token(String type) {
        return new VerifiedToken(1L, OWNER, type, null, null);
    }

    @Test
    void serviceToken_withCanonicalUuid_isTheActingPlayer() {
        assertThat(ActingPlayer.resolve(token("SERVICE"), ALEX)).contains(UUID.fromString(ALEX));
        assertThat(ActingPlayer.resolve(token("SERVICE"), "  " + ALEX.toUpperCase(Locale.ROOT) + " "))
                .contains(UUID.fromString(ALEX));
    }

    @Test
    void absentOrBlankHeader_isEmpty() {
        assertThat(ActingPlayer.resolve(token("SERVICE"), null)).isEmpty();
        assertThat(ActingPlayer.resolve(token("SERVICE"), "   ")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PERSONAL", "BUSINESS", "GOVERNMENT"})
    void nonServiceTokens_neverCarryAnActingPlayer_evenAWellFormedOne(String type) {
        assertThat(ActingPlayer.resolve(token(type), ALEX)).isEmpty();
        assertThat(ActingPlayer.resolve(token(type), "garbage")).isEmpty();
        assertThat(ActingPlayer.resolve(null, ALEX)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "garbage",
                "1-2-3-4-5", // UUID.fromString accepts this; the canonical check must not
                "0b5a1c2d3e4f4a5b8c6d7e8f9a0b1c2d",
                "0b5a1c2d-3e4f-4a5b-8c6d-7e8f9a0b1c2d, 11111111-1111-1111-1111-111111111111",
                "00000000-0000-0000-0000-000000000000",
                "0b5a1c2d-3e4f-4a5b-8c6d-7e8f9a0b1c2d\r\nX-Evil: 1"
            })
    void serviceToken_withMalformedHeader_isA400(String header) {
        ApiException ex =
                catchThrowableOfType(() -> ActingPlayer.resolve(token("SERVICE"), header), ApiException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getErrorCode()).isEqualTo("INVALID_ACTING_PLAYER");
    }

    @Test
    void of_readsOnlyTheValidatedAttribute() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ActingPlayer.HEADER, ALEX);
        assertThat(ActingPlayer.of(request)).isEmpty();
        request.setAttribute(ActingPlayer.ATTRIBUTE, UUID.fromString(ALEX));
        assertThat(ActingPlayer.of(request)).contains(UUID.fromString(ALEX));
        request.setAttribute(ActingPlayer.ATTRIBUTE, ALEX);
        assertThat(ActingPlayer.of(request)).isEmpty();
    }
}
