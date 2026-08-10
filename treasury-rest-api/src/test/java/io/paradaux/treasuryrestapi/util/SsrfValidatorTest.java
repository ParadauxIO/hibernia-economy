package io.paradaux.treasuryrestapi.util;

import io.paradaux.treasuryrestapi.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SSRF guard tests. Uses literal IPs (no DNS) so they're deterministic offline.
 */
class SsrfValidatorTest {

    @Test
    void acceptsHttpsPublicAddress() {
        assertThat(SsrfValidator.validate("https://93.184.216.34/hook").getHost())
                .isEqualTo("93.184.216.34");
    }

    @Test
    void rejectsNonHttps() {
        assertThatThrownBy(() -> SsrfValidator.validate("http://93.184.216.34/hook"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsBlankAndMalformed() {
        assertThatThrownBy(() -> SsrfValidator.validate(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> SsrfValidator.validate("  ")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> SsrfValidator.validate("https://")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> SsrfValidator.validate("not a url")).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsUserInfo() {
        assertThatThrownBy(() -> SsrfValidator.validate("https://user@93.184.216.34/hook"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsNonStandardPort() {
        assertThatThrownBy(() -> SsrfValidator.validate("https://93.184.216.34:22/x"))
                .as("off-port reaches non-HTTP internal services")
                .isInstanceOf(ApiException.class);
        // The default port and an explicit :443 are both fine.
        assertThat(SsrfValidator.validate("https://93.184.216.34/x").getPort()).isEqualTo(-1);
        assertThat(SsrfValidator.validate("https://93.184.216.34:443/x").getPort()).isEqualTo(443);
    }

    @Test
    void rejectsInternalLiteralIps() {
        for (String url : new String[]{
                "https://127.0.0.1/x",        // loopback
                "https://10.0.0.5/x",         // site-local
                "https://192.168.1.1/x",      // site-local
                "https://172.16.0.1/x",       // site-local
                "https://169.254.169.254/x",  // link-local / cloud metadata
                "https://100.64.0.1/x",       // CGNAT
                "https://0.0.0.0/x",          // any-local
                "https://[::1]/x",            // loopback v6
                "https://[fc00::1]/x"         // unique-local v6
        }) {
            assertThatThrownBy(() -> SsrfValidator.validate(url))
                    .as("should reject %s", url)
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    void isBlockedClassifiesAddresses() throws Exception {
        assertThat(SsrfValidator.isBlocked(InetAddress.getByName("127.0.0.1"))).isTrue();
        assertThat(SsrfValidator.isBlocked(InetAddress.getByName("169.254.169.254"))).isTrue();
        assertThat(SsrfValidator.isBlocked(InetAddress.getByName("100.127.255.255"))).isTrue();
        assertThat(SsrfValidator.isBlocked(InetAddress.getByName("fc00::1"))).isTrue();
        assertThat(SsrfValidator.isBlocked(InetAddress.getByName("93.184.216.34"))).isFalse();
        assertThat(SsrfValidator.isBlocked(InetAddress.getByName("8.8.8.8"))).isFalse();
    }
}
