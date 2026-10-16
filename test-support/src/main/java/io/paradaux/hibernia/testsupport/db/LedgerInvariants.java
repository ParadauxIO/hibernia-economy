package io.paradaux.hibernia.testsupport.db;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * The production money-invariant check ({@code ops/money-invariants.sql}) used as a
 * test oracle. The SQL is not copied: the build stages the repo file into this jar
 * (see {@code test-support/build.gradle.kts}), so the check operators run against
 * the live tenant and the check every DB-backed test must pass are the same text.
 *
 * <p>It returns one row: {@code status} ('OK'/'FAIL') plus counters — global sum of
 * postings, unbalanced transactions, materialised-balance mismatches, postings
 * without a balance row, illegally negative accounts, and balances written without
 * the trigger. On FAIL the assertion message carries every counter and a sample of
 * the offending rows, so a broken test points at the account/txn, not just "FAIL".
 */
public final class LedgerInvariants {

    static final String RESOURCE = "/io/paradaux/hibernia/testsupport/db/money-invariants.sql";

    private static final String SQL = load();

    private LedgerInvariants() {}

    /** The raw invariant SQL as shipped in {@code ops/}. */
    public static String sql() {
        return SQL;
    }

    /** Runs the check and returns its single row. */
    public static Result check(DataSource dataSource) {
        try (Connection c = dataSource.getConnection();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(SQL)) {
            if (!rs.next()) throw new IllegalStateException("money-invariants.sql returned no row");
            ResultSetMetaData md = rs.getMetaData();
            Map<String, Object> counters = new LinkedHashMap<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                counters.put(md.getColumnLabel(i), rs.getObject(i));
            }
            return new Result(String.valueOf(counters.get("status")), counters);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to run money-invariants.sql", e);
        }
    }

    /** Fails with the counters (and offending rows) unless the ledger is consistent. */
    public static void assertHold(DataSource dataSource, String context) {
        Result result = check(dataSource);
        if (result.ok()) return;
        throw new AssertionError("Ledger invariants violated after " + context + ": " + result.counters()
                + diagnostics(dataSource)
                + "\nIf the test corrupts the ledger on purpose, annotate it with "
                + "@SkipLedgerInvariants(\"<why>\").");
    }

    /**
     * Small, bounded follow-up queries naming the rows behind each non-zero counter.
     * Only run on failure, so their cost never touches a passing suite.
     */
    private static String diagnostics(DataSource dataSource) {
        StringBuilder out = new StringBuilder();
        sample(
                dataSource,
                out,
                "unbalanced txns",
                "SELECT txn_id, SUM(amount) AS sum FROM ledger_postings GROUP BY txn_id HAVING SUM(amount) <> 0 LIMIT 10");
        sample(
                dataSource,
                out,
                "mat mismatches",
                "SELECT m.account_id, m.balance AS mat, COALESCE(p.s, 0) AS postings FROM account_balances_mat m "
                        + "LEFT JOIN (SELECT account_id, SUM(amount) s FROM ledger_postings GROUP BY account_id) p "
                        + "ON p.account_id = m.account_id WHERE m.balance <> COALESCE(p.s, 0) LIMIT 10");
        sample(
                dataSource,
                out,
                "illegal negatives",
                "SELECT a.account_id, a.account_type, a.allow_overdraft, a.credit_limit, m.balance FROM accounts a "
                        + "JOIN account_balances_mat m ON m.account_id = a.account_id WHERE m.balance < 0 "
                        + "AND a.account_type NOT IN ('GOVERNMENT','SYSTEM') "
                        + "AND (a.allow_overdraft = 0 OR m.balance < -a.credit_limit) LIMIT 10");
        sample(
                dataSource,
                out,
                "mat rows written without trigger",
                "SELECT account_id, balance FROM account_balances_mat WHERE version = 0 AND balance <> 0 LIMIT 10");
        return out.toString();
    }

    private static void sample(DataSource dataSource, StringBuilder out, String label, String sql) {
        try (Connection c = dataSource.getConnection();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            List<String> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                rows.add(row.toString());
            }
            if (!rows.isEmpty()) out.append("\n  ").append(label).append(": ").append(rows);
        } catch (SQLException e) {
            out.append("\n  ")
                    .append(label)
                    .append(": <diagnostic query failed: ")
                    .append(e.getMessage())
                    .append('>');
        }
    }

    private static String load() {
        try (InputStream in = LedgerInvariants.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing from the test-support jar — "
                        + "it is staged from ops/money-invariants.sql by test-support's processResources");
            }
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            // The ops file ends with ';' for the mysql CLI; a JDBC single statement must not.
            return sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The invariant row: {@code status} plus every counter column, in SQL order. */
    public record Result(String status, Map<String, Object> counters) {
        public boolean ok() {
            return "OK".equals(status);
        }

        public long count(String counter) {
            Object value = counters.get(counter);
            if (value == null) {
                throw new IllegalArgumentException("money-invariants.sql has no counter " + counter);
            }
            return ((Number) value).longValue();
        }

        public BigDecimal globalSum() {
            return new BigDecimal(String.valueOf(counters.get("global_sum")));
        }
    }
}
