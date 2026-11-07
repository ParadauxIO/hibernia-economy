package io.paradaux.treasury.mappers;

import static org.assertj.core.api.Assertions.assertThat;

import io.paradaux.treasury.model.economy.*;
import io.paradaux.treasury.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ImmutableSnapshotsIT extends IntegrationTestBase {
    @Test
    void generatedKeysAndConstructorMappingsPreserveSnapshots() {
        AccountMapper accounts = injector.getInstance(AccountMapper.class);
        UUID owner = UUID.randomUUID();
        Account draft = new Account(
                null, AccountType.BUSINESS, owner, "Snapshot fixture", true, false, true, new BigDecimal("123.45"));
        int id = accounts.insertAccount(draft);
        accounts.seedBalance(id);
        assertThat(id).isPositive();
        assertThat(draft.getAccountId()).isNull();
        Account loaded = accounts.findById(id);
        assertThat(loaded).isEqualTo(draft.withAccountId(id));
        accounts.updateAccount(loaded.withDisplayName("Renamed").withArchived(true));
        assertThat(loaded.getDisplayName()).isEqualTo("Snapshot fixture");
        assertThat(loaded.isArchived()).isFalse();
        assertThat(accounts.findById(id).getDisplayName()).isEqualTo("Renamed");
        assertThat(accounts.findById(id).isArchived()).isTrue();

        LedgerMapper ledger = injector.getInstance(LedgerMapper.class);
        byte[] key = new byte[32];
        key[0] = 7;
        LedgerTxn txn = new LedgerTxn(0, null, null, "Snapshot transaction", owner, null, null, key);
        long txnId = ledger.insertTxnEntity(txn);
        assertThat(txnId).isPositive();
        assertThat(txn.getTxnId()).isZero();
        LedgerTxn stored = ledger.findTxnById(txnId);
        assertThat(stored.getTxnId()).isEqualTo(txnId);
        assertThat(stored.getMessage()).isEqualTo("Snapshot transaction");
        assertThat(stored.getInitiatorUuid()).isEqualTo(owner);
        assertThat(stored.getAuthorizerUuid()).isNull();
        assertThat(stored.getPluginSystem()).isNull();
        assertThat(stored.getTradeTime()).isNotNull();
        assertThat(stored.getSettlementTime()).isNotNull();
        stored.getClientDedupKey()[0] = 8;
        assertThat(stored.getClientDedupKey()).isEqualTo(key);
        assertThat(ledger.findByDedupKey(key)).isEqualTo(stored);
    }
}
