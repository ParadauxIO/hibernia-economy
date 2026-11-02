package io.paradaux.treasury.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.paradaux.treasury.mappers.AccountMapper;
import io.paradaux.treasury.mappers.LedgerMapper;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.TransactionEntry;
import io.paradaux.treasury.services.BytebinService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DataExportServiceImplTest {

    @Mock
    AccountMapper accountMapper;

    @Mock
    LedgerMapper ledgerMapper;

    @Mock
    BytebinService bytebinService;

    private DataExportServiceImpl svc;

    @BeforeEach
    void setUp() {
        svc = new DataExportServiceImpl(accountMapper, ledgerMapper, bytebinService);
    }

    @Test
    void exportTransactionsFor_unknownAccount_throws() {
        when(accountMapper.findById(99)).thenReturn(null);

        assertThatThrownBy(() -> svc.exportTransactionsFor(99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Account not found");
    }

    @Test
    void exportTransactionsFor_buildsCsvAndUploadsToBytebin() {
        when(accountMapper.findById(1)).thenReturn(new Account());
        UUID initiator = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(ledgerMapper.findAllTransactionsByAccount(eq(1), anyInt()))
                .thenReturn(List.of(
                        txn(10L, 100L, 1, "100.00", "deposit", initiator, null, "Vault"),
                        txn(11L, 101L, 1, "-50.00", "withdraw", initiator, initiator, "MyPlugin")));
        when(bytebinService.upload(anyString(), eq("text/csv"))).thenReturn("https://x/abc");

        String url = svc.exportTransactionsFor(1);

        assertThat(url).isEqualTo("https://x/abc");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(bytebinService).upload(body.capture(), eq("text/csv"));
        String csv = body.getValue();

        // Header line is present
        assertThat(csv)
                .startsWith(
                        "posting_id,txn_id,account_id,amount,memo,settlement_time,message,initiator_uuid,authorizer_uuid,plugin_system,payout_category\n");
        // Each row contains the txn id and the formatted amount
        assertThat(csv).contains(",100.00,");
        assertThat(csv).contains(",-50.00,");
        assertThat(csv).contains("Vault");
        assertThat(csv).contains("MyPlugin");
    }

    @Test
    void csv_containsStructuredCategorySeparateFromMemo() {
        when(accountMapper.findById(1)).thenReturn(new Account());
        TransactionEntry entry = txn(1L, 1L, 1, "12.50", "memo", null, null, "test");
        entry.setPayoutCategory(io.paradaux.treasury.model.economy.PayoutCategory.AUCTION_PROCEEDS);
        when(ledgerMapper.findAllTransactionsByAccount(eq(1), anyInt())).thenReturn(List.of(entry));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        when(bytebinService.upload(body.capture(), eq("text/csv"))).thenReturn("url");
        svc.exportTransactionsFor(1);
        assertThat(body.getValue()).contains(",test,AUCTION_PROCEEDS\n").contains(",12.50,memo,");
    }

    @Test
    void csv_escapesCommas_quotes_and_newlines() {
        when(accountMapper.findById(1)).thenReturn(new Account());
        when(ledgerMapper.findAllTransactionsByAccount(eq(1), anyInt()))
                .thenReturn(List.of(
                        txn(1L, 1L, 1, "1.00", "memo, has comma", null, null, "P1"),
                        txn(2L, 2L, 1, "1.00", "memo \"quotes\"", null, null, "P2"),
                        txn(3L, 3L, 1, "1.00", "line1\nline2", null, null, "P3")));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        when(bytebinService.upload(body.capture(), eq("text/csv"))).thenReturn("u");
        svc.exportTransactionsFor(1);

        String csv = body.getValue();
        assertThat(csv).contains("\"memo, has comma\"");
        assertThat(csv).contains("\"memo \"\"quotes\"\"\"");
        assertThat(csv).contains("\"line1\nline2\"");
    }

    @Test
    void csv_handlesNullOptionalFields() {
        when(accountMapper.findById(1)).thenReturn(new Account());
        when(ledgerMapper.findAllTransactionsByAccount(eq(1), anyInt()))
                .thenReturn(List.of(txn(1L, 1L, 1, "1.00", null, null, null, null)));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        when(bytebinService.upload(body.capture(), eq("text/csv"))).thenReturn("u");
        svc.exportTransactionsFor(1);

        // Last column (plugin_system) is empty, line ends with \n
        String csv = body.getValue();
        String lastDataLine = csv.substring(csv.indexOf('\n') + 1, csv.length() - 1);
        assertThat(lastDataLine).endsWith(",");
    }

    private static TransactionEntry txn(
            long postingId,
            long txnId,
            int accountId,
            String amount,
            String memo,
            UUID initiator,
            UUID authorizer,
            String pluginSystem) {
        TransactionEntry e = new TransactionEntry();
        e.setPostingId(postingId);
        e.setTxnId(txnId);
        e.setAccountId(accountId);
        e.setAmount(new BigDecimal(amount));
        e.setMemo(memo);
        e.setSettlementTime(Instant.parse("2026-01-01T00:00:00Z"));
        e.setMessage("ledger msg");
        e.setInitiatorUuid(initiator);
        e.setAuthorizerUuid(authorizer);
        e.setPluginSystem(pluginSystem);
        return e;
    }
}
