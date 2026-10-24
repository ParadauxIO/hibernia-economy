package io.paradaux.treasury.services.impl;

import com.google.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import io.paradaux.treasury.mappers.AccountMapper;
import io.paradaux.treasury.mappers.LedgerMapper;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.TransactionEntry;
import io.paradaux.treasury.services.BytebinService;
import io.paradaux.treasury.services.DataExportService;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
public class DataExportServiceImpl implements DataExportService {

    private static final int MAX_EXPORT_ROWS = 50_000;
    private static final DateTimeFormatter CSV_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final AccountMapper accountMapper;
    private final LedgerMapper ledgerMapper;
    private final BytebinService bytebinService;

    @Inject
    public DataExportServiceImpl(AccountMapper accountMapper,
                                 LedgerMapper ledgerMapper,
                                 BytebinService bytebinService) {
        this.accountMapper  = accountMapper;
        this.ledgerMapper   = ledgerMapper;
        this.bytebinService = bytebinService;
    }

    @Override
    public String exportTransactionsFor(int accountId) {
        // NOT @Transactional on purpose (ADT-72): this is a read-only export, so the
        // two lookups are fine in autocommit and each releases its pooled connection
        // immediately. The bytebin upload is a network round-trip — doing it here,
        // outside any transaction, means it never pins a DB connection across that
        // I/O (which under load would starve the pool). Atomicity across the two
        // reads isn't needed for an export.
        Account account = accountMapper.findById(accountId);
        if (account == null) throw new IllegalArgumentException("Account not found: " + accountId);
        List<TransactionEntry> transactions = ledgerMapper.findAllTransactionsByAccount(accountId, MAX_EXPORT_ROWS);
        String csv = buildCsv(transactions);
        String url = bytebinService.upload(csv, "text/csv");
        log.debug("Exported {} transactions for account {} -> {}", transactions.size(), accountId, url);
        return url;
    }

    private String buildCsv(List<TransactionEntry> transactions) {
        StringBuilder sb = new StringBuilder();
        sb.append("posting_id,txn_id,account_id,amount,memo,settlement_time,message,initiator_uuid,authorizer_uuid,plugin_system\n");
        for (TransactionEntry entry : transactions) {
            sb.append(entry.getPostingId()).append(',');
            sb.append(entry.getTxnId()).append(',');
            sb.append(entry.getAccountId()).append(',');
            sb.append(entry.getAmount().toPlainString()).append(',');
            sb.append(escapeCsv(entry.getMemo())).append(',');
            sb.append(entry.getSettlementTime() != null
                    ? CSV_TIME_FMT.format(entry.getSettlementTime()) : "").append(',');
            sb.append(escapeCsv(entry.getMessage())).append(',');
            sb.append(entry.getInitiatorUuid() != null
                    ? entry.getInitiatorUuid().toString() : "").append(',');
            sb.append(entry.getAuthorizerUuid() != null
                    ? entry.getAuthorizerUuid().toString() : "").append(',');
            sb.append(entry.getPluginSystem() != null ? entry.getPluginSystem() : "");
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
