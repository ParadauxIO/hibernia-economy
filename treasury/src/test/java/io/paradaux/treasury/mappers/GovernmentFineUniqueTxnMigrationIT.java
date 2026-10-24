package io.paradaux.treasury.mappers;

import io.paradaux.treasury.model.economy.GovernmentFine;
import io.paradaux.treasury.services.GovService;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V29 must be applicable to a database that already holds the duplicate fine rows
 * it exists to prevent. The harness only ever migrates an empty schema, so this
 * re-creates the pre-V29 state (no {@code uq_fines_txn}, duplicate {@code txn_id}
 * rows) and replays the real migration script over it.
 */
class GovernmentFineUniqueTxnMigrationIT extends IntegrationTestBase {

    private static final String MIGRATION = "db/migration/V29__government_fines_unique_txn.sql";

    private GovService govService;
    private LedgerService ledgerService;

    @BeforeEach
    void setUp() {
        govService    = injector.getInstance(GovService.class);
        ledgerService = injector.getInstance(LedgerService.class);
        ledgerService.bootstrapGovernmentAccounts();
    }

    @Test
    void migration_collapsesExistingDuplicatesThenAddsTheUniqueKey() throws Exception {
        UUID issuer = UUID.randomUUID();
        GovernmentFine plain   = issue("plain duplicate", issuer);
        GovernmentFine revoked = issue("duplicate that was revoked", issuer);
        GovernmentFine single  = issue("never duplicated", issuer);

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            try {
                // Back to the pre-V29 shape. fk_fines_txn needs some index on txn_id.
                st.execute("ALTER TABLE government_fines DROP INDEX uq_fines_txn, "
                        + "ADD INDEX tmp_fines_txn (txn_id)");
                long plainDup   = duplicate(c, plain.getFineId(), false);
                long revokedDup = duplicate(c, revoked.getFineId(), true);
                assertThat(fineIds(c)).hasSize(5);

                for (String sql : migrationStatements()) {
                    st.execute(sql);
                }

                // Unrevoked group → the lowest fine_id survives. A group with a revoked
                // row → the revoked row survives, so the already-refunded debit can't
                // be revoked (refunded) a second time through the other row.
                assertThat(fineIds(c)).containsExactlyInAnyOrder(
                        plain.getFineId(), revokedDup, single.getFineId());
                assertThat(fineIds(c)).doesNotContain(plainDup, revoked.getFineId());
                assertThat(hasUniqueTxnKey(c)).isTrue();
            } finally {
                // Leave the shared schema as the harness built it, whatever happened.
                st.execute("ALTER TABLE government_fines DROP INDEX IF EXISTS tmp_fines_txn");
                if (!hasUniqueTxnKey(c)) {
                    st.execute("DELETE FROM government_fines");
                    st.execute("ALTER TABLE government_fines ADD UNIQUE KEY uq_fines_txn (txn_id)");
                }
            }
        }
    }

    private GovernmentFine issue(String reason, UUID issuer) {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        return govService.issueFine(player, new BigDecimal("10.00"), reason, issuer);
    }

    /** Inserts a second fine row sharing {@code fineId}'s debit; returns the new fine_id. */
    private static long duplicate(Connection c, long fineId, boolean revoked) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO government_fines (player_uuid_bin, debtor_account_id, gov_account_id, amount, "
                        + "reason, txn_id, issued_by_uuid_bin, revoked) "
                        + "SELECT player_uuid_bin, debtor_account_id, gov_account_id, amount, reason, txn_id, "
                        + "issued_by_uuid_bin, ? FROM government_fines WHERE fine_id = ?",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setBoolean(1, revoked);
            ps.setLong(2, fineId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static List<Long> fineIds(Connection c) throws Exception {
        List<Long> ids = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT fine_id FROM government_fines")) {
            while (rs.next()) ids.add(rs.getLong(1));
        }
        return ids;
    }

    private static boolean hasUniqueTxnKey(Connection c) throws Exception {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.statistics "
                             + "WHERE table_schema = DATABASE() AND table_name = 'government_fines' "
                             + "AND index_name = 'uq_fines_txn' AND non_unique = 0")) {
            rs.next();
            return rs.getLong(1) > 0;
        }
    }

    /** The migration script's statements, comments stripped. */
    private static List<String> migrationStatements() throws Exception {
        String script;
        try (InputStream in = GovernmentFineUniqueTxnMigrationIT.class.getClassLoader()
                .getResourceAsStream(MIGRATION)) {
            assertThat(in).as("migration on the test classpath: %s", MIGRATION).isNotNull();
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String sql = script.lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .collect(Collectors.joining("\n"));
        List<String> statements = new ArrayList<>();
        for (String statement : sql.split(";")) {
            if (!statement.isBlank()) statements.add(statement.strip());
        }
        return statements;
    }
}
