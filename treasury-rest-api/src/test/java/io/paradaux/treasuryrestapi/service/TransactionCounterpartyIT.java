package io.paradaux.treasuryrestapi.service;

import io.paradaux.treasuryrestapi.dto.TransactionFeedResponse;
import io.paradaux.treasuryrestapi.dto.TransactionItem;
import io.paradaux.treasuryrestapi.dto.TransactionItem.Counterparty;
import io.paradaux.treasuryrestapi.dto.TransactionsResponse;
import io.paradaux.treasuryrestapi.mapper.WebhookDeliveryMapper;
import io.paradaux.treasuryrestapi.model.DueDelivery;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import io.paradaux.treasuryrestapi.testsupport.EmbeddedDbIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PAR-15: every transaction item carries the account on the other leg —
 * history, cursor feed and webhook payloads alike.
 */
class TransactionCounterpartyIT extends EmbeddedDbIT {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private WebhookDeliveryMapper deliveryMapper;

    private static final UUID PLAYER = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID FOUNDER = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID GOV = UUID.fromString("66666666-6666-6666-6666-666666666666");

    private static final Counterparty FIRM =
            new Counterparty(2, "BUSINESS", "Acme Corporate Account", FOUNDER.toString());
    private static final Counterparty TREASURY =
            new Counterparty(3, "GOVERNMENT", "Treasury", GOV.toString());

    private VerifiedToken token() {
        return new VerifiedToken(1L, PLAYER, "PERSONAL", 1L, null);
    }

    @BeforeEach
    void seed() {
        insertAccount(1, "PERSONAL", PLAYER, PLAYER.toString());
        insertAccount(2, "BUSINESS", FOUNDER, "Acme Corporate Account");
        insertAccount(3, "GOVERNMENT", GOV, "Treasury");
    }

    @Test
    void history_showsTheOtherLegForDebitsAndCredits() {
        insertSettledTransfer(1, 2, "25.00", "buy", PLAYER);   // player pays the firm
        insertSettledTransfer(3, 1, "100.00", "wage", GOV);    // treasury pays the player

        TransactionsResponse resp = transactionService.getTransactions(token(), 1, 1, 20);

        assertThat(resp.totalItems()).isEqualTo(2);
        List<TransactionItem> items = resp.items();
        assertThat(items).hasSize(2);
        // newest first
        assertThat(items.get(0).amount()).isEqualTo("100.00");
        assertThat(items.get(0).counterparty()).isEqualTo(TREASURY);
        assertThat(items.get(1).amount()).isEqualTo("-25.00");
        assertThat(items.get(1).counterparty()).isEqualTo(FIRM);
    }

    @Test
    void history_counterpartyIsThePlayerFromTheFirmSide() {
        insertSettledTransfer(1, 2, "25.00", "buy", PLAYER);

        TransactionsResponse resp = transactionService.getTransactions(
                new VerifiedToken(2L, FOUNDER, "PERSONAL", 2L, null), 2, 1, 20);

        assertThat(resp.items()).singleElement().satisfies(item -> {
            assertThat(item.amount()).isEqualTo("25.00");
            assertThat(item.counterparty())
                    .isEqualTo(new Counterparty(1, "PERSONAL", PLAYER.toString(), PLAYER.toString()));
        });
    }

    @Test
    void history_singleLegTransaction_hasNullCounterparty() {
        insertSettledTxn(1, "5.00", "orphan", PLAYER);

        TransactionsResponse resp = transactionService.getTransactions(token(), 1, 1, 20);

        assertThat(resp.items()).singleElement()
                .satisfies(item -> assertThat(item.counterparty()).isNull());
    }

    @Test
    void history_extraLegsDoNotDuplicateRows() {
        // Not something the ledger writes today, but the join must not fan out.
        long txn = insertSettledTransfer(1, 2, "25.00", "split", PLAYER);
        exec("INSERT INTO ledger_postings (txn_id, account_id, amount, memo) VALUES (?, 3, 0.00, 'fee')",
                ps -> ps.setLong(1, txn));

        TransactionsResponse resp = transactionService.getTransactions(token(), 1, 1, 20);

        assertThat(resp.totalItems()).isEqualTo(1);
        assertThat(resp.items()).singleElement()
                .satisfies(item -> assertThat(item.counterparty()).isEqualTo(FIRM));
    }

    @Test
    void history_paginatesWithCounterparties() {
        for (int i = 0; i < 5; i++) {
            insertSettledTransfer(1, 2, "1.00", "p" + i, PLAYER);
        }

        TransactionsResponse page2 = transactionService.getTransactions(token(), 1, 2, 2);

        assertThat(page2.totalItems()).isEqualTo(5);
        assertThat(page2.items()).extracting(TransactionItem::memo).containsExactly("p2", "p1");
        assertThat(page2.items()).allSatisfy(item -> assertThat(item.counterparty()).isEqualTo(FIRM));
    }

    @Test
    void feed_showsCounterpartiesOldestFirst() {
        long first = insertSettledTransfer(1, 2, "25.00", "buy", PLAYER);
        long second = insertSettledTransfer(3, 1, "100.00", "wage", GOV);

        TransactionFeedResponse feed = transactionService.getTransactionFeed(token(), 1, 0, 10);

        assertThat(feed.items()).extracting(TransactionItem::txnId).containsExactly(first, second);
        assertThat(feed.items()).extracting(TransactionItem::counterparty).containsExactly(FIRM, TREASURY);
    }

    @Test
    void webhookPayload_carriesCounterparty() {
        long sub = insertSubscription(PLAYER, "PERSONAL", 1, null, "https://example.com/hook",
                "s".repeat(64), true, 0);
        long txn = insertSettledTransfer(3, 1, "100.00", "wage", GOV);
        insertDueDelivery(sub, txn, 1, 0);

        List<DueDelivery> due = deliveryMapper.findDue(10);

        assertThat(due).singleElement().satisfies(d -> {
            assertThat(d.getAmount()).isEqualByComparingTo("100.00");
            assertThat(d.getCounterpartyAccountId()).isEqualTo(3L);
            assertThat(d.getCounterpartyAccountType()).isEqualTo("GOVERNMENT");
            assertThat(d.getCounterpartyDisplayName()).isEqualTo("Treasury");
            assertThat(d.getCounterpartyOwnerUuidBin()).isEqualTo(GOV);
        });
    }
}
