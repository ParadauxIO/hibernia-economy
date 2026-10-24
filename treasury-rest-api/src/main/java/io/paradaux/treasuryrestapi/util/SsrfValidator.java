package io.paradaux.treasuryrestapi.util;

import io.paradaux.treasuryrestapi.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/**
 * SSRF guard for player-supplied webhook URLs. The dispatcher POSTs to these
 * from inside the cluster, so a URL resolving to a private/internal address
 * could be used to reach internal services or the cloud metadata endpoint.
 *
 * <p>Enforced both at registration time and again immediately before each
 * delivery (DNS can be re-pointed after registration — a "DNS rebinding"
 * attack). Requires {@code https}, an explicit host, the standard https port
 * (443), and that <b>every</b> resolved address is a public unicast address.
 *
 * <p><b>Residual (ADT-27):</b> the delivery-time re-check is the rebinding
 * mitigation, but a narrow TOCTOU remains — the JDK {@code HttpClient} re-resolves
 * DNS at connect with no resolver hook, so it cannot be pinned to the address this
 * validator vetted. Eliminating it entirely needs a custom transport that connects
 * to the vetted literal IP while preserving Host/SNI; the re-check keeps the window
 * to milliseconds.
 */
public final class SsrfValidator {

    private SsrfValidator() {}

    /**
     * Validates the URL for registration. Throws {@link ApiException} (400) if
     * the URL is malformed, not https, or resolves to a non-public address.
     *
     * @return the parsed {@link URI}
     */
    public static URI validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw bad("Webhook url is required.");
        }
        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (URISyntaxException e) {
            throw bad("Webhook url is not a valid URL.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw bad("Webhook url must use https.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw bad("Webhook url must include a host.");
        }
        if (uri.getUserInfo() != null) {
            throw bad("Webhook url must not contain user info.");
        }
        int port = uri.getPort();
        if (port != -1 && port != 443) {
            // Enforce the standard https port (ADT-27). A public host on an
            // off-port (e.g. :22, :6379) is a route to reach a non-HTTP internal
            // service the resolved-address checks below don't catch.
            throw bad("Webhook url must use the default https port (443).");
        }
        if (!isPublicHost(host)) {
            throw bad("Webhook url must resolve to a public address.");
        }
        return uri;
    }

    /**
     * Re-checks at delivery time. Returns false (rather than throwing) so the
     * dispatcher can mark the delivery failed instead of erroring.
     */
    public static boolean isPublicHost(String host) {
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            if (addrs.length == 0) return false;
            for (InetAddress a : addrs) {
                if (isBlocked(a)) return false;
            }
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** True if the address is anything other than a routable public unicast address. */
    public static boolean isBlocked(InetAddress a) {
        if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int o0 = b[0] & 0xFF, o1 = b[1] & 0xFF;
            if (o0 == 0) return true;                       // 0.0.0.0/8
            if (o0 == 100 && o1 >= 64 && o1 <= 127) return true; // 100.64.0.0/10 CGNAT
        } else if (b.length == 16) {
            if ((b[0] & 0xFE) == 0xFC) return true;         // fc00::/7 unique-local
        }
        return false;
    }

    private static ApiException bad(String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_WEBHOOK_URL", msg);
    }
}
