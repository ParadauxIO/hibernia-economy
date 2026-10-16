package net.democracycraft.inttests.restapi;

import com.fasterxml.jackson.databind.JsonNode;
import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.ApiTokens;
import net.democracycraft.inttests.support.ApiTokens.IssuedKey;
import net.democracycraft.inttests.support.Barrier;
import net.democracycraft.inttests.support.BusinessLedger;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.RestApi;
import net.democracycraft.inttests.support.RestApi.Response;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E coverage for BUSINESS-typed API tokens. A BUSINESS key is firm-scoped:
 * the JWT carries a {@code firm} claim and the REST API is supposed to
 * resolve the firm's default account as the implied {@code acc} for transfers
 * and history.
 */
class BusinessTokenTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static String freshFirm(String tag) {
        return tag + Long.toHexString(Instant.now().toEpochMilli());
    }

    @Test
    void businessTokenIssuesAndPersistsWithFirmId() {
        String owner = Usernames.fresh("Bo");
        String firm = freshFirm("Bf");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            // Create the firm and seed funds into its default account.
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                // Deposit 1000 into the firm's default account.
                Barrier.settle();
                p.chatCommand("/business deposit " + firm + " 1000");
                p.awaitMessageContaining("Deposited", TIMEOUT);
            });

            IssuedKey key = ApiTokens.issueBusiness(owner, p, firm);

            assertThat(key.firmId()).isNotNull();
            assertThat(key.accountId())
                    .as("BUSINESS keys are firm-scoped — account_id is null in api_keys")
                    .isNull();
            assertThat(key.token()).isNotBlank();
        }
    }

    @Test
    void businessTokenCanTransferFromFirmDefaultAccount() {
        // BUSINESS keys must specify fromAccountId — the API rejects the
        // request otherwise (the JWT carries firm_id, but a firm can have
        // many accounts so the caller picks). The fromAccountId must be
        // owned by the same firm as the key. We pass the firm's default
        // account id explicitly.
        String owner = Usernames.fresh("Bx");
        String recipient = Usernames.fresh("Br");
        String firm = freshFirm("Bxf");

        try (MockPlayer o = MockPlayer.connectLocal(owner);
             MockPlayer r = MockPlayer.connectLocal(recipient)) {
            Ledger.waitForPersonalBalance(o.getUuid(), TIMEOUT);
            BigDecimal recipientStart = Ledger.waitForPersonalBalance(r.getUuid(), TIMEOUT);

            int[] firmId = new int[1];
            Ops.run(owner, () -> {
                o.chatCommand("/business create " + firm);
                o.awaitMessageContaining("Successfully created", TIMEOUT);
                firmId[0] = BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                o.chatCommand("/business deposit " + firm + " 500");
                o.awaitMessageContaining("Deposited", TIMEOUT);
            });

            IssuedKey key = ApiTokens.issueBusiness(owner, o, firm);
            int firmDefaultAccount = BusinessLedger.firmDefaultAccountId(firmId[0]).orElseThrow();
            int recipientAccount = Ledger.personalAccountId(r.getUuid()).orElseThrow();

            String body = "{\"fromAccountId\":" + firmDefaultAccount
                    + ",\"toAccountId\":" + recipientAccount
                    + ",\"amount\":\"100.00\",\"memo\":\"firm-pay\"}";
            Response t = RestApi.postTransfer(key.token(), body, null);
            assertThat(t.status())
                    .as("response: %s", t.body())
                    .isEqualTo(200);

            JsonNode tb = t.json();
            assertThat(tb.path("fromAccountId").asInt())
                    .as("BUSINESS token must transfer from the firm's default account")
                    .isEqualTo(firmDefaultAccount);
            assertThat(tb.path("toAccountId").asInt()).isEqualTo(recipientAccount);
            assertThat(tb.path("amount").asText()).isEqualTo("100.00");

            // DB-state assertions: firm balance debited, recipient credited.
            Ledger.waitForPersonalBalance(r.getUuid(),
                    recipientStart.add(new BigDecimal("100.00")), TIMEOUT);
            assertThat(BusinessLedger.firmDefaultBalance(firmId[0]).orElseThrow())
                    .isEqualByComparingTo(new BigDecimal("400.00"));
        }
    }

    @Test
    void businessTokenCanReadOwnFirmTransactions() {
        String owner = Usernames.fresh("Bh");
        String firm = freshFirm("Bhf");

        try (MockPlayer o = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(o.getUuid(), TIMEOUT);

            int[] firmId = new int[1];
            Ops.run(owner, () -> {
                o.chatCommand("/business create " + firm);
                o.awaitMessageContaining("Successfully created", TIMEOUT);
                firmId[0] = BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                o.chatCommand("/business deposit " + firm + " 250");
                o.awaitMessageContaining("Deposited", TIMEOUT);
            });

            IssuedKey key = ApiTokens.issueBusiness(owner, o, firm);
            int firmDefault = BusinessLedger.firmDefaultAccountId(firmId[0]).orElseThrow();

            Response r = RestApi.getTransactions(key.token(), firmDefault, null, null);
            assertThat(r.status())
                    .as("response: %s", r.body())
                    .isEqualTo(200);
            assertThat(r.json().path("totalItems").asInt()).isGreaterThan(0);
        }
    }

    @Test
    void businessTokenRotateProducesUsableNewToken() {
        String owner = Usernames.fresh("Br");
        String firm = freshFirm("Brf");

        try (MockPlayer o = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(o.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                o.chatCommand("/business create " + firm);
                o.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);
            });

            IssuedKey before = ApiTokens.issueBusiness(owner, o, firm);

            Response r = RestApi.postRotate(before.token());
            assertThat(r.status())
                    .as("rotate response: %s", r.body())
                    .isEqualTo(200);
            String newToken = r.json().path("token").asText();
            assertThat(newToken).isNotEqualTo(before.token());

            // Old token now superseded.
            Response old = RestApi.postRotate(before.token());
            assertThat(old.status()).isEqualTo(401);
            assertThat(old.error()).isEqualTo("TOKEN_SUPERSEDED");
        }
    }
}
