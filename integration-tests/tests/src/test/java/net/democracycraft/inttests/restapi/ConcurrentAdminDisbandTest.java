package net.democracycraft.inttests.restapi;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

class ConcurrentAdminDisbandTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void simultaneousRequestsRefundEachAccountOnceAndConvergeOnArchivedState() throws Exception {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("CdOwn"));
             MockPlayer operator = MockPlayer.connectLocal(Usernames.fresh("CdOp"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(operator.getUuid(), TIMEOUT);
            String firm = Usernames.fresh("CdFrm");
            setup(owner, "/business create " + firm);
            int id = BusinessLedger.waitForFirm(firm, TIMEOUT);
            int primary = BusinessLedger.firmDefaultAccountId(id).orElseThrow();
            setup(owner, "/business account create " + firm + " Reserve" + firm);
            int reserve = BusinessLedger.firmAccountIdAt(id, 1);
            setup(owner, "/paya account " + primary + " 23.45 ConcurrentPrimary");
            setup(owner, "/paya account " + reserve + " 67.89 ConcurrentReserve");
            int personal = Ledger.personalAccountId(owner.getUuid()).orElseThrow();
            String token = ApiTokens.mintServiceKey(operator.getUuid()).token();
            long checkpoint = Ledger.transactionCheckpoint();
            int callers = 8;
            CountDownLatch ready = new CountDownLatch(callers);
            CountDownLatch start = new CountDownLatch(1);
            List<RestApi.Response> responses = new ArrayList<>();
            try (var workers = Executors.newFixedThreadPool(callers)) {
                List<Future<RestApi.Response>> futures = new ArrayList<>();
                for (int i = 0; i < callers; i++) {
                    futures.add(workers.submit(() -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Request start barrier timed out");
                        return RestApi.call("POST", "/admin/firms/" + id + "/disband", token, null);
                    }));
                }
                boolean allReady = ready.await(10, TimeUnit.SECONDS);
                start.countDown();
                assertThat(allReady).isTrue();
                for (var future : futures) responses.add(future.get(20, TimeUnit.SECONDS));
            }
            var postings = Ledger.initiatedPostingsAfter(owner.getUuid(), checkpoint);
            List<Executable> checks = new ArrayList<>();
            for (var response : responses) {
                checks.add(() -> assertThat(response.status()).as("response %s", response.body()).isEqualTo(200));
                if (response.status() == 200) {
                    checks.add(() -> assertThat(response.json().path("firmId").asInt()).isEqualTo(id));
                    checks.add(() -> assertThat(response.json().path("archived").asBoolean()).isTrue());
                }
            }
            checks.add(() -> assertThat(BusinessLedger.firmIsArchived(firm)).isTrue());
            checks.add(() -> assertThat(BusinessLedger.firmDefaultAccountId(id)).isEmpty());
            checks.add(() -> assertThat(BusinessLedger.activeFirmAccountCount(id)).isZero());
            checks.add(() -> assertThat(BusinessLedger.firmAccountCount(id)).isEqualTo(2));
            checks.add(() -> assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000"));
            checks.add(() -> assertThat(Ledger.personalBalance(operator.getUuid()).orElseThrow()).isEqualByComparingTo("10000"));
            for (int account : List.of(primary, reserve)) {
                checks.add(() -> assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo("0"));
                checks.add(() -> assertThat(Ledger.postingsCountForAccount(account)).isEqualTo(2));
            }
            checks.add(() -> assertThat(postings).hasSize(4));
            checks.add(() -> assertThat(Ledger.initiatedTransactionCountAfter(owner.getUuid(), checkpoint)).isEqualTo(2));
            checks.add(() -> assertThat(postings.stream().filter(p -> p.accountId() == primary).map(Ledger.Posting::amount).toList()).containsExactly(new BigDecimal("-23.45")));
            checks.add(() -> assertThat(postings.stream().filter(p -> p.accountId() == reserve).map(Ledger.Posting::amount).toList()).containsExactly(new BigDecimal("-67.89")));
            checks.add(() -> assertThat(postings.stream().filter(p -> p.accountId() == personal).map(Ledger.Posting::amount).toList()).containsExactlyInAnyOrder(new BigDecimal("23.45"), new BigDecimal("67.89")));
            assertAll(checks);
            List<String> paidReceipts = new ArrayList<>();
            for (var response : responses) {
                for (var row : response.json().path("accounts")) {
                    if (!row.path("sweptAmount").isNull()) {
                        assertThat(row.path("toAccountId").asInt()).isEqualTo(personal);
                        paidReceipts.add(row.path("accountId").asInt() + ":" + row.path("sweptAmount").asText());
                    }
                }
            }
            assertThat(paidReceipts).containsExactlyInAnyOrder(primary + ":23.45", reserve + ":67.89");
        }
    }
    private static void setup(MockPlayer player, String command) {
        Ops.run(player.getUsername(), () -> { player.chatCommand(command); Barrier.settle(); });
    }
}
