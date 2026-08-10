package io.paradaux.treasury.services.impl;

import com.sun.net.httpserver.HttpServer;
import io.paradaux.treasury.model.config.FineWebhookConfiguration;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.GovernmentFine;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.PlayerDirectoryService;
import io.paradaux.treasury.testsupport.TestConfigs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

class FineWebhookServiceImplTest {

    private HttpServer server;
    private final AtomicReference<String> lastPayload = new AtomicReference<>();
    private final AtomicInteger callCount = new AtomicInteger();
    private volatile int responseStatus = 200;

    private AccountService accountService;
    private PlayerDirectoryService playerDirectory;

    private static final UUID OFFENDER = UUID.randomUUID();
    private static final UUID ISSUER   = UUID.randomUUID();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            callCount.incrementAndGet();
            lastPayload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resp = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();

        accountService = Mockito.mock(AccountService.class);
        playerDirectory = Mockito.mock(PlayerDirectoryService.class);

        Account gov = Mockito.mock(Account.class);
        lenient().when(gov.getDisplayName()).thenReturn("DCGovernment");
        lenient().when(accountService.getAccountById(50)).thenReturn(gov);
        // Default to "unknown" (empty) first, then override the known UUIDs — Mockito
        // resolves overlapping matchers last-wins, so the specific stubs must follow.
        lenient().when(playerDirectory.resolveNameByUuid(any())).thenReturn(Optional.empty());
        lenient().when(playerDirectory.resolveNameByUuid(OFFENDER)).thenReturn(Optional.of("Offender"));
        lenient().when(playerDirectory.resolveNameByUuid(ISSUER)).thenReturn(Optional.of("OfficerRian"));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private FineWebhookServiceImpl svc(boolean enabled, String url) {
        FineWebhookConfiguration cfg = TestConfigs.fineWebhook(enabled, url);
        return new FineWebhookServiceImpl(cfg, accountService, playerDirectory);
    }

    private String hookUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private GovernmentFine playerFine() {
        return GovernmentFine.builder()
                .fineId(7)
                .playerUuid(OFFENDER)
                .debtorAccountId(12)
                .govAccountId(50)
                .amount(new BigDecimal("250.00"))
                .reason("speeding")
                .txnId(98765)
                .issuedBy(ISSUER)
                .build();
    }

    @Test
    void disabled_skipsHttpCall() {
        svc(false, hookUrl()).sendFineIssued(playerFine());
        assertThat(callCount).hasValue(0);
    }

    @Test
    void enabledButBlankUrl_skipsHttpCall() {
        svc(true, "").sendFineIssued(playerFine());
        assertThat(callCount).hasValue(0);
    }

    @Test
    void sendFineIssued_postsRedEmbedWithDetails() {
        svc(true, hookUrl()).sendFineIssued(playerFine());

        assertThat(callCount).hasValue(1);
        String body = lastPayload.get();
        assertThat(body).contains("\"color\":15548997");   // 0xED4245 red
        assertThat(body).contains("Fine Issued");
        assertThat(body).contains("Offender");
        assertThat(body).contains("$250.00");
        assertThat(body).contains("DCGovernment");
        assertThat(body).contains("OfficerRian");
        assertThat(body).contains("speeding");
        assertThat(body).contains("Fine #7");
        assertThat(body).contains("txn #98765");
    }

    @Test
    void sendFineRevoked_postsGreenEmbedWithReverseTxn() {
        GovernmentFine fine = playerFine();
        fine.setRevoked(true);
        fine.setRevokedBy(ISSUER);
        fine.setRevokeTxnId(55555L);

        svc(true, hookUrl()).sendFineRevoked(fine);

        String body = lastPayload.get();
        assertThat(body).contains("\"color\":5763719");    // 0x57F287 green
        assertThat(body).contains("Fine Revoked");
        assertThat(body).contains("Amount Refunded");
        assertThat(body).contains("reverse txn #55555");
    }

    @Test
    void firmFine_targetsTheDebtedAccountName() {
        Account business = Mockito.mock(Account.class);
        when(business.getDisplayName()).thenReturn("Acme Co.");
        when(accountService.getAccountById(12)).thenReturn(business);

        GovernmentFine firmFine = GovernmentFine.builder()
                .fineId(8).playerUuid(null).debtorAccountId(12).govAccountId(50)
                .amount(new BigDecimal("1000.00")).reason("dumping").txnId(1).issuedBy(ISSUER)
                .build();

        svc(true, hookUrl()).sendFineIssued(firmFine);

        assertThat(lastPayload.get()).contains("Acme Co.");
    }

    @Test
    void escapesQuotesAndBackslashesInReason() {
        GovernmentFine fine = playerFine();
        fine.setReason("evil\"reason\\with\nnewline");

        svc(true, hookUrl()).sendFineIssued(fine);

        assertThat(lastPayload.get()).contains("evil\\\"reason\\\\with\\nnewline");
    }

    @Test
    void serverError_doesNotThrow() {
        responseStatus = 500;
        svc(true, hookUrl()).sendFineIssued(playerFine());
        assertThat(callCount).hasValue(1);
    }

    @Test
    void unreachableHost_swallowsException() {
        svc(true, "http://127.0.0.1:1/never-listens").sendFineIssued(playerFine());
    }
}
