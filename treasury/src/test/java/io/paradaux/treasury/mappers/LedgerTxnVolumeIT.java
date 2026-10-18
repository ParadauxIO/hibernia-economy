package io.paradaux.treasury.mappers;

import static org.assertj.core.api.Assertions.assertThat;

import io.paradaux.treasury.model.economy.TransferRequest;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.testsupport.IntegrationTestBase;
import io.paradaux.treasury.utils.TreasuryConstants;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Coverage of the V36 triggers that maintain {@code ledger_txn_volume}, the
 * per-transaction settlement time and positive-posting volume the explorer's
 * dashboard aggregates read instead of joining the ledger (PAR-407).
 *
 * <p>Every test asserts the defining property: each {@code ledger_txns} row has
 * exactly one volume row with the same settlement time and a volume equal to the
 * sum of its positive postings.
 */
class LedgerTxnVolumeIT extends IntegrationTestBase {

    @Test
    void insert_recordsSettlementTimeAndPositiveVolume() throws Exception {
        int gov = account("GOVERNMENT");
        int alice = account("PERSONAL");
        int bob = account("PERSONAL");

        long t = txn();
        posting(t, gov, "-10.00");
        posting(t, alice, "6.00");
        posting(t, bob, "4.00");

        assertThat(volume(t)).isEqualByComparingTo("10.00");
        assertVolumeMatchesLedger();
    }

    @Test
    void insert_txnWithoutPostings_stillHasARow() throws Exception {
        long t = txn();

        assertThat(volume(t)).isEqualByComparingTo("0.00");
        assertVolumeMatchesLedger();
    }

    @Test
    void update_postingAmountsAndSigns_adjustTheVolume() throws Exception {
        // Two faucets, so reversing the direction leaves no account illegally negative.
        int gov = account("GOVERNMENT");
        int treasury = account("GOVERNMENT");
        long t = txn();
        long debit = posting(t, gov, "-10.00");
        long credit = posting(t, treasury, "10.00");

        exec("UPDATE ledger_postings SET amount = -7.25 WHERE posting_id = " + debit);
        exec("UPDATE ledger_postings SET amount = 7.25 WHERE posting_id = " + credit);
        assertThat(volume(t)).isEqualByComparingTo("7.25");

        // Reverse the direction: the old credit leaves the volume, the new one enters it.
        exec("UPDATE ledger_postings SET amount = 7.25 WHERE posting_id = " + debit);
        exec("UPDATE ledger_postings SET amount = -7.25 WHERE posting_id = " + credit);
        assertThat(volume(t)).isEqualByComparingTo("7.25");
        assertVolumeMatchesLedger();
    }

    @Test
    void update_movingAPostingToAnotherTxn_movesItsVolume() throws Exception {
        int gov = account("GOVERNMENT");
        int alice = account("PERSONAL");
        long t1 = txn();
        long t2 = txn();
        long debit = posting(t1, gov, "-5.00");
        long credit = posting(t1, alice, "5.00");

        exec("UPDATE ledger_postings SET txn_id = " + t2 + " WHERE posting_id IN (" + debit + ", " + credit + ")");

        assertThat(volume(t1)).isEqualByComparingTo("0.00");
        assertThat(volume(t2)).isEqualByComparingTo("5.00");
        assertVolumeMatchesLedger();
    }

    @Test
    void delete_posting_reversesItsVolume() throws Exception {
        int gov = account("GOVERNMENT");
        int alice = account("PERSONAL");
        long t = txn();
        long debit = posting(t, gov, "-3.00");
        long credit = posting(t, alice, "3.00");

        exec("DELETE FROM ledger_postings WHERE posting_id IN (" + debit + ", " + credit + ")");

        assertThat(volume(t)).isEqualByComparingTo("0.00");
        assertVolumeMatchesLedger();
    }

    @Test
    void update_settlementTime_isFollowed() throws Exception {
        long t = txn();

        exec("UPDATE ledger_txns SET settlement_time = '2026-01-02 03:04:05' WHERE txn_id = " + t);

        assertThat(scalar("SELECT settlement_time = '2026-01-02 03:04:05' FROM ledger_txn_volume WHERE txn_id = " + t))
                .isEqualTo("1");
        assertVolumeMatchesLedger();
    }

    @Test
    void deleteTxn_cascadesToItsVolumeRow() throws Exception {
        long t = txn();

        exec("DELETE FROM ledger_txns WHERE txn_id = " + t);

        assertThat(count("SELECT COUNT(*) FROM ledger_txn_volume WHERE txn_id = " + t)).isZero();
    }

    @Test
    void concurrentUnlimitedAndLimitedTransfers_neverDeadlock_andStayExact() throws Exception {
        // The volume triggers lock only the row their own transaction inserted, so they
        // must not add a lock cycle to LedgerServiceImpl's ordering. Mix the two lock
        // paths: unlimited faucet transfers (no FOR UPDATE; balance locks taken by the
        // posting trigger) and limited transfers (ascending FOR UPDATE first) over the
        // same accounts, in both directions.
        LedgerService ledger = injector.getInstance(LedgerService.class);
        int faucet = account("GOVERNMENT");
        exec("UPDATE accounts SET allow_overdraft = 1, credit_limit = -1 WHERE account_id = " + faucet);
        int a = account("PERSONAL");
        int b = account("PERSONAL");
        ledger.transfer(request(faucet, a, new BigDecimal("10000.00")));
        ledger.transfer(request(faucet, b, new BigDecimal("10000.00")));

        int workers = 24;
        int transfersPerWorker = 5;
        CyclicBarrier startGate = new CyclicBarrier(workers);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int w = 0; w < workers; w++) {
            int kind = w % 3;
            tasks.add(() -> {
                startGate.await(30, TimeUnit.SECONDS);
                for (int i = 0; i < transfersPerWorker; i++) {
                    switch (kind) {
                        case 0 -> ledger.transfer(request(faucet, b, new BigDecimal("1.00")));
                        case 1 -> ledger.transfer(request(a, b, new BigDecimal("3.00")));
                        default -> ledger.transfer(request(b, a, new BigDecimal("7.00")));
                    }
                }
                return null;
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try {
            for (Future<Void> f : pool.invokeAll(tasks, 120, TimeUnit.SECONDS)) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    errors.add(e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors).as("no deadlock or failure with the volume triggers in place").isEmpty();
        assertThat(count("SELECT COUNT(*) FROM ledger_txn_volume"))
                .isEqualTo(count("SELECT COUNT(*) FROM ledger_txns"))
                .isEqualTo(2 + workers * transfersPerWorker);
        // 40 faucet transfers of 1.00, 40 of 3.00 and 40 of 7.00, plus the two seeds.
        assertThat(scalar("SELECT SUM(volume) FROM ledger_txn_volume")).isEqualTo("20440.00");
        assertVolumeMatchesLedger();
    }

    // ---- helpers ----

    private static TransferRequest request(int from, int to, BigDecimal amount) {
        return new TransferRequest(
                from, to, amount, "volume IT", TreasuryConstants.VIRTUAL_TREASURY_INITIATOR, null, "test", null);
    }

    /** Every txn has one volume row, with its settlement time and Σ of its positive postings. */
    private void assertVolumeMatchesLedger() throws SQLException {
        assertThat(count("""
                SELECT COUNT(*) FROM ledger_txns lt
                LEFT JOIN ledger_txn_volume v ON v.txn_id = lt.txn_id
                LEFT JOIN (SELECT txn_id, SUM(CASE WHEN amount > 0 THEN amount ELSE 0 END) AS s
                             FROM ledger_postings GROUP BY txn_id) p ON p.txn_id = lt.txn_id
                WHERE v.txn_id IS NULL
                   OR v.settlement_time <> lt.settlement_time
                   OR v.volume <> COALESCE(p.s, 0)
                """)).as("txns whose ledger_txn_volume row is missing or wrong").isZero();
        assertThat(count("SELECT COUNT(*) FROM ledger_txn_volume"))
                .isEqualTo(count("SELECT COUNT(*) FROM ledger_txns"));
    }

    private BigDecimal volume(long txnId) throws SQLException {
        return new BigDecimal(scalar("SELECT volume FROM ledger_txn_volume WHERE txn_id = " + txnId));
    }

    private int account(String type) throws SQLException {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO accounts (account_type, owner_uuid_bin) VALUES (?, UNHEX(REPLACE(UUID(), '-', '')))",
                        Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, type);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private long txn() throws SQLException {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO ledger_txns (message, initiator_uuid_bin) VALUES ('volume IT', UNHEX(REPLACE(UUID(), '-', '')))",
                        Statement.RETURN_GENERATED_KEYS)) {
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long posting(long txnId, int accountId, String amount) throws SQLException {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO ledger_postings (txn_id, account_id, amount) VALUES (?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, txnId);
            ps.setInt(2, accountId);
            ps.setBigDecimal(3, new BigDecimal(amount));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long count(String sql) throws SQLException {
        return Long.parseLong(scalar(sql));
    }

    private String scalar(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).as("row for: %s", sql).isTrue();
            return rs.getString(1);
        }
    }

    private void exec(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection();
                Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
