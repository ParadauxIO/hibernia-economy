package io.paradaux.treasury.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.paradaux.treasury.internal.model.config.BytebinConfiguration;
import io.paradaux.treasury.testsupport.TestConfigs;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BytebinServiceImplTest {

    private HttpServer server;
    private BytebinConfiguration config;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastContentType = new AtomicReference<>();
    private final AtomicReference<String> lastContentEncoding = new AtomicReference<>();
    private volatile int responseStatus = 200;
    private volatile String responseBody = "{\"key\":\"abc123\"}";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/post", exchange -> {
            lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            lastContentEncoding.set(exchange.getRequestHeaders().getFirst("Content-Encoding"));
            try (InputStream in = "gzip".equalsIgnoreCase(lastContentEncoding.get())
                    ? new GZIPInputStream(exchange.getRequestBody())
                    : exchange.getRequestBody()) {
                lastBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] resp = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();

        int port = server.getAddress().getPort();
        config = TestConfigs.bytebin("http://127.0.0.1:" + port + "/post", "https://example.test/");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void upload_postsGzippedBody_andReturnsBaseUrlWithKey() {
        BytebinServiceImpl svc = new BytebinServiceImpl(config);

        String url = svc.upload("hello world", "text/plain");

        assertThat(url).isEqualTo("https://example.test/abc123");
        assertThat(lastContentType.get()).isEqualTo("text/plain");
        assertThat(lastContentEncoding.get()).isEqualTo("gzip");
        assertThat(lastBody.get()).isEqualTo("hello world");
    }

    @Test
    void upload_serverNon2xx_throwsIllegalState() {
        responseStatus = 503;
        responseBody = "down";
        BytebinServiceImpl svc = new BytebinServiceImpl(config);

        assertThatThrownBy(() -> svc.upload("x", "text/plain"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("503");
    }

    @Test
    void upload_responseMissingKey_throws() {
        responseBody = "{\"unexpected\":\"field\"}";
        BytebinServiceImpl svc = new BytebinServiceImpl(config);

        assertThatThrownBy(() -> svc.upload("x", "text/plain")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void upload_unreachableEndpoint_wrapsIoExceptionInIllegalState() {
        // Stop the server first; the next upload attempt should wrap the
        // ConnectException/IOException in an IllegalStateException.
        server.stop(0);
        BytebinServiceImpl svc = new BytebinServiceImpl(config);

        assertThatThrownBy(() -> svc.upload("x", "text/plain"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to upload");
    }
}
