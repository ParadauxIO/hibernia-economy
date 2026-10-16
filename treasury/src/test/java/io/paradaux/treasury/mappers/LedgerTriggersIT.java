package io.paradaux.treasury.mappers;

import io.paradaux.hibernia.testsupport.db.LedgerInvariants;
import io.paradaux.hibernia.testsupport.db.SkipLedgerInvariants;
import io.paradaux.treasury.model.economy.TransferRequest;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.testsupport.IntegrationTestBase;
import io.paradaux.treasury.utils.TreasuryConstants;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Direct coverage of the three {@code ledger_postings} triggers that maintain
 * {@code account_balances_mat} (V1__initial.sql). Application code only ever INSERTs
 * postings, so {@code trg_postings_au} / {@code trg_postings_ad} were untested — but
 * they are what keeps the materialised balance honest when an operator corrects a
 * posting by hand, and the treasury-rest-api writer relies on the same triggers.
 *
 * <p>Every test drives the triggers with raw SQL and then asserts the defining
 * property: for every account, {@code account_balances_mat.balance} equals
 * {@code Σ ledger_postings.amount}. The harness's post-test invariant check
 * ({@code ops/money-invariants.sql}) re-asserts the same thing independently.
 *
 * <p>Also pins two schema-level facts the ledger's safety rests on: a cascaded
 * delete bypasses the triggers (a known InnoDB limitation — documented here so it
 * isn't rediscovered in production), and a balance that would overflow
 * {@code DECIMAL(19,2)} is rejected atomically rather than clamped.
 */
class LedgerTriggersIT extends IntegrationTestBase {

    /** Largest value a DECIMAL(19,2) column holds: 17 integer digits. */
    private static final BigDecimal DECIMAL_19_2_MAX = new BigDecimal("99999999999999999.99");

    @Test
    void insert_createsMatRowThenAccumulates() throws Exception {
        int gov = account("GOVERNMENT");
        int alice = account("PERSONAL");

        long t1 = txn();
        posting(t1, gov, "-10.00");
        posting(t1, alice, "10.00");
        long t2 = txn();
        posting(t2, gov, "-2.50");
        posting(t2, alice, "2.50");

        assertMatMatchesPostings();
        assertThat(mat(alice)).isEqualByComparingTo("12.50");
        assertThat(version(alice)).isEqualTo(2);
    }

    @Test
    void update_sameAccount_appliesOnlyTheDelta() throws Exception {
        int gov = account("GOVERNMENT");
        int alice = account("PERSONAL");
        long t = txn();
        long debit = posting(t, gov, "-10.00");
        long credit = posting(t, alice, "10.00");

        // Correct the amount on both legs so the txn stays balanced.
        exec("UPDATE ledger_postings SET amount = -7.25 WHERE posting_id = " + debit);
        exec("UPDATE ledger_postings SET amount = 7.25 WHERE posting_id = " + credit);

        assertMatMatchesPostings();
        assertThat(mat(alice)).isEqualByComparingTo("7.25");
        assertThat(mat(gov)).isEqualByComparingTo("-7.25");
        assertThat(version(alice)).isEqualTo(2); // insert + one update
    }

    @Test
    void update_movingPostingToAnotherAccount_reversesOldAndAppliesNew() throws Exception {
        int gov = account("GOVERNMENT");
        int alice = account("PERSONAL");
        int bob = account("PERSONAL"); // no mat row yet: the trigger's INSERT branch creates it
        long t = txn();
        posting(t, gov, "-40.00");
        long credit = posting(t, alice, "40.00");
        assertThat(matRowExists(bob)).isFalse();

        exec("UPDATE ledger_postings SET account_id = " + bob + " WHERE posting_id = " + credit);

        assertMatMatchesPostings();
        assertThat(mat(alice)).isEqualByComparingTo("0.00");
        assertThat(mat(bob)).isEqualByComparingTo("40.00");
        assertThat(version(bob)).isEqualTo(1);
    }

    @Test
    void update_movingAndResizingInOneStatement_staysConsistent() throws Exception {
        int gov = account("GOVERNMENT");
        int alice = account("PERSONAL");
        int bob = account("PERSONAL");
        long t = txn();
        long debit = posting(t, gov, "-40.00");
        long credit = posting(t, alice, "40.00");
        posting(txn(), bob, "5.00", gov, "-5.00");

        exec("UPDATE ledger_postings SET account_id = " + bob + ", amount = 15.00 WHERE posting_id = " + credit);
        exec("UPDATE ledger_postings SET amount = -15.00 WHERE posting_id = " + debit);

        assertMatMatchesPostings();
        assertThat(mat(alice)).isEqualByComparingTo("0.00");
        assertThat(mat(bob)).isEqualByComparingTo("20.00");
    }

    @Test
    void delete_postings_reverseTheirAmounts() throws Exception {
        int gov = account("GOVERNMENT");
        int alice = account("PERSONAL");
        posting(txn(), gov, "-30.00", alice, "30.00");
        long t = txn();
        posting(t, gov, "-12.34");
        posting(t, alice, "12.34");

        exec("DELETE FROM ledger_postings WHERE txn_id = " + t);

        assertMatMatchesPostings();
        assertThat(mat(alice)).isEqualByComparingTo("30.00");
        assertThat(mat(gov)).isEqualByComparingTo("-30.00");
    }

    @Test
    void delete_everyPosting_leavesZeroBalanceRowsBehind() throws Exception {
        int gov = account("GOVERNMENT");
        int alice = account("PERSONAL");
        posting(txn(), gov, "-1.00", alice, "1.00");

        exec("DELETE FROM ledger_postings");

        assertMatMatchesPostings();
        // The AD trigger only adjusts; the row survives at 0 with a bumped version, which
        // keeps mat_balance_without_trigger (version = 0 AND balance <> 0) meaningful.
        assertThat(matRowExists(alice)).isTrue();
        assertThat(mat(alice)).isEqualByComparingTo("0.00");
        assertThat(version(alice)).isEqualTo(2);
    }

    /**
     * Pins a hazard rather than a feature: {@code fk_post_txn} is {@code ON DELETE
     * CASCADE}, and InnoDB never fires triggers for rows removed by a cascading
     * foreign-key action. Deleting a {@code ledger_txns} row therefore removes its
     * postings <em>without</em> {@code trg_postings_ad}, leaving
     * {@code account_balances_mat} out of step with the postings. No application path
     * deletes txns today, so this is only reachable by manual cleanup — which must
     * delete the postings first (as this test shows works) and the txn second. If a
     * future migration makes the FK RESTRICT, flip this test.
     */
    @Test
    @SkipLedgerInvariants("demonstrates that a cascaded txn delete bypasses trg_postings_ad and drifts the mat table")
    void deleteTxn_cascadesPostingsWithoutFiringTheTrigger_andTheInvariantCheckCatchesIt() throws Exception {
        int gov = account("GOVERNMENT");
        int alice = account("PERSONAL");
        long t = txn();
        posting(t, gov, "-9.99", alice, "9.99");

        exec("DELETE FROM ledger_txns WHERE txn_id = " + t);

        assertThat(count("SELECT COUNT(*) FROM ledger_postings WHERE txn_id = " + t)).isZero();
        assertThat(mat(alice)).as("mat row still carries the cascaded-away credit")
                .isEqualByComparingTo("9.99");
        LedgerInvariants.Result result = LedgerInvariants.check(dataSource);
        assertThat(result.ok()).isFalse();
        assertThat(result.count("mat_mismatches")).isEqualTo(2);

        // The safe order: postings first (trigger fires), then the txn.
        long t2 = txn();
        posting(t2, gov, "-1.00", alice, "1.00");
        exec("DELETE FROM ledger_postings WHERE txn_id = " + t2);
        exec("DELETE FROM ledger_txns WHERE txn_id = " + t2);
        assertThat(mat(alice)).isEqualByComparingTo("9.99"); // unchanged by the safe delete
    }

    @Test
    void invariantCheck_detectsAHandEditedMatRow() throws Exception {
        int gov = account("GOVERNMENT");
        int alice = account("PERSONAL");
        posting(txn(), gov, "-5.00", alice, "5.00");
        assertThat(LedgerInvariants.check(dataSource).ok()).isTrue();

        exec("UPDATE account_balances_mat SET balance = balance + 1 WHERE account_id = " + alice);
        LedgerInvariants.Result drifted = LedgerInvariants.check(dataSource);
        assertThat(drifted.ok()).isFalse();
        assertThat(drifted.count("mat_mismatches")).isEqualTo(1);

        exec("UPDATE account_balances_mat SET balance = balance - 1 WHERE account_id = " + alice);
    }

    // ---- DECIMAL(19,2) bounds ----

    @Test
    void transfer_beyondDecimal19_2_isRejectedAtomically_notClamped() throws Exception {
        // Relies on STRICT_TRANS_TABLES (the MariaDB default, and what production runs:
        // verified 2026-10-16). Without strict mode MariaDB would silently CLAMP an
        // out-of-range balance to the column maximum — i.e. create money.
        assertThat(scalar("SELECT @@SESSION.sql_mode")).contains("STRICT_TRANS_TABLES");

        LedgerService ledger = injector.getInstance(LedgerService.class);
        int faucet = account("GOVERNMENT");
        exec("UPDATE accounts SET allow_overdraft = 1, credit_limit = -1 WHERE account_id = " + faucet);
        int whale = account("PERSONAL");

        // Fill the recipient to just under the column maximum, leaving the faucet at the
        // matching negative (the column is symmetric, so the faucet is equally close).
        BigDecimal nearMax = DECIMAL_19_2_MAX.subtract(new BigDecimal("0.99"));
        ledger.transfer(request(faucet, whale, nearMax));
        long txnsBefore = count("SELECT COUNT(*) FROM ledger_txns");
        long postingsBefore = count("SELECT COUNT(*) FROM ledger_postings");

        // 1.00 more would put both balances past ±99,999,999,999,999,999.99.
        assertThatThrownBy(() -> ledger.transfer(request(faucet, whale, new BigDecimal("1.00"))))
                .hasStackTraceContaining("Out of range");
        // A single amount that doesn't fit the posting column at all.
        int fresh = account("PERSONAL");
        assertThatThrownBy(() -> ledger.transfer(request(faucet, fresh, new BigDecimal("100000000000000000.00"))))
                .hasStackTraceContaining("Out of range");

        // Rolled back as a unit: no orphan txn header, no half-written posting pair.
        assertThat(count("SELECT COUNT(*) FROM ledger_txns")).isEqualTo(txnsBefore);
        assertThat(count("SELECT COUNT(*) FROM ledger_postings")).isEqualTo(postingsBefore);
        assertThat(mat(whale)).isEqualByComparingTo(nearMax);
        assertMatMatchesPostings();
    }

    // ---- helpers ----

    private static TransferRequest request(int from, int to, BigDecimal amount) {
        return new TransferRequest(from, to, amount, "overflow probe",
                TreasuryConstants.VIRTUAL_TREASURY_INITIATOR, null, "test", null);
    }

    /** Asserts mat.balance == Σ postings for every account (missing mat row counts as 0). */
    private void assertMatMatchesPostings() throws SQLException {
        Map<Integer, BigDecimal[]> rows = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT a.account_id, COALESCE(m.balance, 0) AS mat,
                            (SELECT COALESCE(SUM(p.amount), 0) FROM ledger_postings p
                              WHERE p.account_id = a.account_id) AS posted
                       FROM accounts a LEFT JOIN account_balances_mat m ON m.account_id = a.account_id
                     """)) {
            while (rs.next()) {
                rows.put(rs.getInt(1), new BigDecimal[] {rs.getBigDecimal(2), rs.getBigDecimal(3)});
            }
        }
        assertThat(rows).isNotEmpty();
        rows.forEach((id, r) -> assertThat(r[0]).as("account %d: mat vs Σ postings", id).isEqualByComparingTo(r[1]));
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
                     "INSERT INTO ledger_txns (message, initiator_uuid_bin) VALUES ('trigger IT', UNHEX(REPLACE(UUID(), '-', '')))",
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

    private void posting(long txnId, int a, String amountA, int b, String amountB) throws SQLException {
        posting(txnId, a, amountA);
        posting(txnId, b, amountB);
    }

    private BigDecimal mat(int accountId) throws SQLException {
        return new BigDecimal(scalar("SELECT balance FROM account_balances_mat WHERE account_id = " + accountId));
    }

    private long version(int accountId) throws SQLException {
        return count("SELECT version FROM account_balances_mat WHERE account_id = " + accountId);
    }

    private boolean matRowExists(int accountId) throws SQLException {
        return count("SELECT COUNT(*) FROM account_balances_mat WHERE account_id = " + accountId) > 0;
    }

    private long count(String sql) throws SQLException {
        return Long.parseLong(scalar(sql));
    }

    private String scalar(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).as("row for: %s", sql).isTrue();
            return rs.getString(1);
        }
    }

    private void exec(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
