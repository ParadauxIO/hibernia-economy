package net.democracycraft.inttests.restapi;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

class PersonalOwnershipKeyTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @ValueSource(strings = {"transfer", "history"})
    void formerOwnersPersonalKeyCannotAccessReassignedAccount(String operation) {
        try (MockPlayer oldOwner = MockPlayer.connectLocal(Usernames.fresh("PkOld"));
             MockPlayer peer = MockPlayer.connectLocal(Usernames.fresh("PkPe"))) {
            Ledger.waitForPersonalBalance(oldOwner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(peer.getUuid(), TIMEOUT);
            int account = Ledger.personalAccountId(oldOwner.getUuid()).orElseThrow();
            int destination = Ledger.personalAccountId(peer.getUuid()).orElseThrow();
            var oldKey = ApiTokens.issuePersonal(oldOwner.getUsername(), oldOwner);
            assertThat(pay(oldKey.token(), destination, "1.25", "BeforeOwnershipChange").status()).isEqualTo(200);
            assertThat(RestApi.getTransactions(oldKey.token(), account, null, null).status()).isEqualTo(200);
            UUID newOwner = UUID.randomUUID();
            String service = ApiTokens.mintServiceKey(peer.getUuid()).token();
            move(service, account, newOwner);
            assertThat(Ledger.personalAccountId(newOwner)).hasValue(account);
            assertThat(Ledger.personalAccountId(oldOwner.getUuid())).isEmpty();
            long checkpoint = Ledger.transactionCheckpoint();
            var response = operation.equals("transfer")
                    ? pay(oldKey.token(), destination, "7.89", "FormerOwnerMustBeDenied")
                    : RestApi.getTransactions(oldKey.token(), account, null, null);
            assertAll(
                    () -> assertThat(response.status()).as("%s", response.body()).isIn(401, 403),
                    () -> assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo("9998.75"),
                    () -> assertThat(Ledger.accountBalanceById(destination).orElseThrow()).isEqualByComparingTo("10001.25"),
                    () -> assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint));
        }
    }

    @Test
    void newOwnerUsesExistingAccountAfterFirstJoinWithoutSecondStartingGrant() {
        try (MockPlayer creator = MockPlayer.connectLocal(Usernames.fresh("PnOld"));
             MockPlayer peer = MockPlayer.connectLocal(Usernames.fresh("PnPe"))) {
            Ledger.waitForPersonalBalance(creator.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(peer.getUuid(), TIMEOUT);
            int account = Ledger.personalAccountId(creator.getUuid()).orElseThrow();
            int destination = Ledger.personalAccountId(peer.getUuid()).orElseThrow();
            String newName = Usernames.fresh("PnNew");
            UUID newId = UUID.nameUUIDFromBytes(("OfflinePlayer:" + newName).getBytes(StandardCharsets.UTF_8));
            String service = ApiTokens.mintServiceKey(peer.getUuid()).token();
            long checkpoint = Ledger.transactionCheckpoint();
            move(service, account, newId);
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
            try (MockPlayer newOwner = MockPlayer.connectLocal(newName)) {
                assertThat(newOwner.getUuid()).isEqualTo(newId);
                Ledger.waitForPersonalBalance(newId, TIMEOUT);
                Barrier.settle();
                assertThat(Ledger.personalAccountId(newId)).hasValue(account);
                assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo("10000");
                assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
                var newKey = ApiTokens.issuePersonal(newName, newOwner);
                assertThat(newKey.accountId()).isEqualTo(account);
                var paid = pay(newKey.token(), destination, "23.45", "NewOwnerPayment");
                assertThat(paid.status()).as("%s", paid.body()).isEqualTo(200);
                assertThat(paid.json().path("fromAccountId").asInt()).isEqualTo(account);
                assertThat(paid.json().path("toAccountId").asInt()).isEqualTo(destination);
                assertThat(paid.json().path("amount").asText()).isEqualTo("23.45");
                assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo("9976.55");
                assertThat(Ledger.accountBalanceById(destination).orElseThrow()).isEqualByComparingTo("10023.45");
                assertThat(Ledger.initiatedPostingsAfter(newId, checkpoint)).hasSize(2);
                assertThat(Ledger.initiatedTransactionCountAfter(newId, checkpoint)).isEqualTo(1);
                assertThat(Ledger.personalAccountId(creator.getUuid())).isEmpty();
            }
        }
    }
    private static void move(String service, int account, UUID owner) {
        // PAR-501 keeps the previous player's name reserved to that player.
        // Rename explicitly before transferring the financial identity.
        var renamed = RestApi.call("PATCH", "/admin/accounts/" + account + "/display-name", service,
                "{\"displayName\":\"" + Usernames.fresh("PkAls") + "\"}");
        assertThat(renamed.status()).as(renamed.body()).isEqualTo(200);
        var response = RestApi.call("PATCH", "/admin/accounts/" + account + "/owner", service, "{\"owner\":\"" + owner + "\"}");
        assertThat(response.status()).as("%s", response.body()).isEqualTo(200);
        assertThat(response.json().path("accountId").asInt()).isEqualTo(account);
        assertThat(response.json().path("accountType").asText()).isEqualTo("PERSONAL");
        assertThat(response.json().path("ownerUuid").asText()).isEqualTo(owner.toString());
    }
    private static RestApi.Response pay(String token, int destination, String amount, String memo) {
        return RestApi.postTransfer(token, "{\"toAccountId\":" + destination + ",\"amount\":\"" + amount + "\",\"memo\":\"" + memo + "\"}", null);
    }
}
