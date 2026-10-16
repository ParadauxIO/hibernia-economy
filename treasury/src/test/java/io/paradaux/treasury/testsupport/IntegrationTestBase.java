package io.paradaux.treasury.testsupport;

import com.google.inject.Guice;
import com.google.inject.Injector;
import io.paradaux.hibernia.testsupport.db.TestDatabase;
import io.paradaux.hibernia.testsupport.db.TestDatabaseExtension;
import io.paradaux.treasury.guice.DatabaseModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Base class for integration tests that hit a real MariaDB — embedded MariaDB4j
 * locally, or the server named by {@code TREASURY_TEST_JDBC_URL} in CI — through the
 * shared {@link TestDatabaseExtension} harness in test-support.
 *
 * <p>The database is booted and migrated once per JVM and shared across every
 * subclass. Before each test the harness resets every table to its just-migrated
 * state; after each test it runs {@code ops/money-invariants.sql} and fails the test
 * if the ledger was left inconsistent (opt out with {@code @SkipLedgerInvariants}).
 *
 * <p>Subclasses receive a fully-wired Guice {@link Injector} with the production
 * service implementations and {@link TestServicesModule} bindings for configurations
 * and stubbed out-of-process collaborators.
 */
@Tag("integration")
public abstract class IntegrationTestBase {

    public static final TestDatabase DATABASE = TestDatabase.of("treasury_test", "TREASURY");

    @RegisterExtension
    static final TestDatabaseExtension DB = new TestDatabaseExtension(DATABASE);

    protected DataSource dataSource;
    protected Injector injector;

    @BeforeEach
    void setUpIntegration() {
        this.dataSource = DB.dataSource();
        this.injector = Guice.createInjector(
                new DatabaseModule(dataSource),
                new TestServicesModule()
        );
    }

    /**
     * Seeds the parent rows the V20 ChestShop market foreign keys reference
     * ({@code chestshop_sale.txn_id → ledger_txns}, {@code shop_account_id →
     * accounts}, {@code shop_firm_id → firm}) under the fixed ids the market ITs use.
     *
     * <p>Those ITs used to drop the five FKs instead, which removed them for the rest
     * of the JVM (and, on a persistent database, for every later run), so every other
     * IT then ran against a weaker schema than production. Seeding the referenced
     * rows keeps the constraints in force. The rows carry no postings, so the ledger
     * invariants are unaffected. Explicit ids are fine here: none of these parents has
     * a foreign key of its own that would need FK checks relaxed.
     */
    protected void seedMarketReferences(long[] txnIds, int[] accountIds, int[] firmIds) {
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO ledger_txns (txn_id, message, initiator_uuid_bin) VALUES (?, 'market IT fixture', ?)")) {
                for (long id : txnIds) {
                    ps.setLong(1, id);
                    ps.setBytes(2, uuidBytes(UUID.randomUUID()));
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO accounts (account_id, account_type, owner_uuid_bin, display_name) "
                            + "VALUES (?, 'BUSINESS', ?, ?)")) {
                for (int id : accountIds) {
                    ps.setInt(1, id);
                    ps.setBytes(2, uuidBytes(UUID.randomUUID()));
                    ps.setString(3, "market-fixture-" + id);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO firm (firm_id, display_name, proprietor_uuid_bin) VALUES (?, ?, ?)")) {
                for (int id : firmIds) {
                    ps.setInt(1, id);
                    ps.setString(2, "Market Fixture Firm " + id);
                    ps.setBytes(3, uuidBytes(UUID.randomUUID()));
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to seed ChestShop market FK parents", e);
        }
    }

    private static byte[] uuidBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }
}
