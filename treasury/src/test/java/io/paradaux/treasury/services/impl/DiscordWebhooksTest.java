package io.paradaux.treasury.services.impl;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

class DiscordWebhooksTest {

    private HttpServer server;
    /** Released in teardown so the stalled handler thread can exit. */
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Accepts the request and then never answers — an unresponsive webhook.
        server.createContext("/stall", exchange -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        server.stop(0);
    }

    @Test
    void post_givesUpOnAnUnresponsiveEndpointInsteadOfBlockingTheCaller() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/stall";
        long start = System.nanoTime();

        // Without a request timeout HttpClient.send blocks until the peer answers,
        // pinning the command thread (and everything queued after the webhook).
        assertThatCode(() -> DiscordWebhooks.post(
                HttpClient.newHttpClient(), url, "{}", "Test Webhook", Duration.ofMillis(300)))
                .doesNotThrowAnyException();

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void post_appliesTheDefaultRequestTimeout() throws Exception {
        HttpClient client = Mockito.mock(HttpClient.class);

        DiscordWebhooks.post(client, "http://127.0.0.1:1/hook", "{}", "Test Webhook");

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), any());
        assertThat(request.getValue().timeout()).contains(DiscordWebhooks.REQUEST_TIMEOUT);
    }
}
