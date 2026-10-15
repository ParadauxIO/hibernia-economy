package io.paradaux.treasuryrestapi.service;

import io.paradaux.treasuryrestapi.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookTargetPolicyTest {

    private static WebhookTargetPolicy withTargets(String... targets) {
        return new WebhookTargetPolicy(List.of(targets), new MockEnvironment());
    }

    @Test
    void strictPolicy_rejectsLoopbackAndAcceptsPublicHttps() {
        WebhookTargetPolicy policy = WebhookTargetPolicy.strict();

        assertThatThrownBy(() -> policy.validate("http://127.0.0.1:18090/hook"))
                .isInstanceOf(ApiException.class);
        assertThat(policy.isDeliverable(URI.create("http://127.0.0.1:18090/hook"))).isFalse();
        assertThat(policy.validate("https://93.184.216.34/hook").getHost()).isEqualTo("93.184.216.34");
        assertThat(policy.isDeliverable(URI.create("https://93.184.216.34/hook"))).isTrue();
    }

    @Test
    void testTarget_bypassesChecksForExactHostAndPortOnly() {
        WebhookTargetPolicy policy = withTargets(" 127.0.0.1:18090 ");

        assertThat(policy.validate("http://127.0.0.1:18090/hook").getPort()).isEqualTo(18090);
        assertThat(policy.isDeliverable(URI.create("http://127.0.0.1:18090/any/path"))).isTrue();
        assertThat(policy.isDeliverable(URI.create("https://127.0.0.1:18090/hook"))).isTrue();

        for (String url : List.of(
                "http://127.0.0.1:18091/hook",     // other port
                "http://127.0.0.1/hook",           // implicit :80
                "http://localhost:18090/hook",     // other host spelling
                "http://user@127.0.0.1:18090/x",   // user info
                "ftp://127.0.0.1:18090/hook")) {   // other scheme
            assertThatThrownBy(() -> policy.validate(url)).as(url).isInstanceOf(ApiException.class);
            assertThat(policy.isDeliverable(URI.create(url))).as(url).isFalse();
        }
    }

    @Test
    void testTarget_implicitPortsFollowTheScheme() {
        WebhookTargetPolicy policy = withTargets("127.0.0.1:80");

        assertThat(policy.isDeliverable(URI.create("http://127.0.0.1/hook"))).isTrue();
        assertThat(policy.isDeliverable(URI.create("https://127.0.0.1/hook"))).isFalse();
    }

    @Test
    void refusesToStartWithTestTargetsUnderDeployedProfiles() {
        for (String profile : List.of("prod", "uat")) {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles(profile);
            assertThatThrownBy(() -> new WebhookTargetPolicy(List.of("127.0.0.1:18090"), env))
                    .as(profile)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(profile);
        }
    }

    @Test
    void deployedProfilesStartNormallyWithoutTestTargets() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        WebhookTargetPolicy policy = new WebhookTargetPolicy(List.of(), env);

        assertThat(policy.isDeliverable(URI.create("http://127.0.0.1:18090/hook"))).isFalse();
    }
}
