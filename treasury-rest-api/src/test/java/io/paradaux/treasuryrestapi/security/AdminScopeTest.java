package io.paradaux.treasuryrestapi.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.paradaux.treasuryrestapi.exception.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

/**
 * {@link AdminScope} is the service-level SERVICE-key gate behind every admin endpoint
 * (PAR-210/PAR-217). SecurityConfig enforces the same rule at the URL; this pins the
 * service-level half, which also protects direct service callers. The HTTP-level
 * behaviour for every admin route is covered by {@link RouteSecurityIT}.
 */
class AdminScopeTest {

    private static VerifiedToken token(String keyType) {
        return new VerifiedToken(1L, UUID.randomUUID(), keyType, null, null);
    }

    @Test
    void serviceKey_passes() {
        assertThatCode(() -> AdminScope.require(token("SERVICE"))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PERSONAL", "BUSINESS", "GOVERNMENT", "service", ""})
    void anyOtherKeyType_isForbidden(String keyType) {
        assertForbidden(token(keyType));
    }

    @Test
    void missingPrincipal_isForbidden() {
        assertForbidden(null);
    }

    private static void assertForbidden(VerifiedToken token) {
        ApiException ex = catchThrowableOfType(ApiException.class, () -> AdminScope.require(token));
        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ex.getErrorCode()).isEqualTo("FORBIDDEN");
    }
}
