package io.paradaux.treasuryrestapi.service;

import io.paradaux.treasuryrestapi.util.SsrfValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides which webhook URLs may be registered and delivered to. Everything
 * goes through {@link SsrfValidator} except URLs whose {@code host:port}
 * exactly matches an entry in {@code treasury.webhook.insecure-test-targets}.
 *
 * <p>That setting exists only so the live integration stack
 * ({@code ops/integration/run.sh}) can deliver to a local HTTP receiver. It is
 * empty by default, deliberately not mapped to an env var in
 * {@code application.yaml}, and the app refuses to start with it set under the
 * {@code prod} or {@code uat} profile.
 */
@Component
public class WebhookTargetPolicy {

    private static final Logger log = LoggerFactory.getLogger(WebhookTargetPolicy.class);
    private static final Set<String> DEPLOYED_PROFILES = Set.of("prod", "uat");

    /** Normalised {@code host:port} entries that bypass the SSRF checks. */
    private final Set<String> testTargets;

    @Autowired
    public WebhookTargetPolicy(@Value("${treasury.webhook.insecure-test-targets:}") List<String> testTargets,
                               Environment env) {
        this.testTargets = testTargets.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        if (this.testTargets.isEmpty()) {
            return;
        }
        List<String> deployed = Arrays.stream(env.getActiveProfiles())
                .filter(DEPLOYED_PROFILES::contains)
                .toList();
        if (!deployed.isEmpty()) {
            throw new IllegalStateException("treasury.webhook.insecure-test-targets is for the integration "
                    + "test stack only and must not be set under profile(s) " + deployed);
        }
        log.warn("Webhook SSRF checks are BYPASSED for test targets {} — integration testing only",
                this.testTargets);
    }

    /** The production policy: no test targets. */
    public static WebhookTargetPolicy strict() {
        return new WebhookTargetPolicy(List.of(), new StandardEnvironment());
    }

    /**
     * Validates a URL for registration; see {@link SsrfValidator#validate}.
     *
     * @return the parsed {@link URI}
     */
    public URI validate(String rawUrl) {
        URI testTarget = asTestTarget(rawUrl);
        return testTarget != null ? testTarget : SsrfValidator.validate(rawUrl);
    }

    /** Delivery-time re-check; see {@link SsrfValidator#isPublicHost}. */
    public boolean isDeliverable(URI uri) {
        if (isTestTarget(uri)) {
            return true;
        }
        return uri.getHost() != null && SsrfValidator.isPublicHost(uri.getHost());
    }

    private URI asTestTarget(String rawUrl) {
        if (testTargets.isEmpty() || rawUrl == null) {
            return null;
        }
        try {
            URI uri = new URI(rawUrl.trim());
            return isTestTarget(uri) ? uri : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private boolean isTestTarget(URI uri) {
        if (testTargets.isEmpty() || uri.getHost() == null || uri.getUserInfo() != null) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if ("http".equals(scheme)) {
            port = port == -1 ? 80 : port;
        } else if ("https".equals(scheme)) {
            port = port == -1 ? 443 : port;
        } else {
            return false;
        }
        return testTargets.contains(uri.getHost().toLowerCase(Locale.ROOT) + ":" + port);
    }
}
