package io.paradaux.treasury.testsupport;

import com.google.inject.Guice;
import com.google.inject.Injector;
import io.paradaux.treasury.guice.DatabaseModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Base class for integration tests that hit a real MariaDB via Testcontainers.
 *
 * <p>The container is started once per JVM and shared across every subclass.
 * Each test method gets a clean slate via {@link MariadbContainerExtension#truncateAll}.
 *
 * <p>Subclasses receive a fully-wired Guice {@link Injector} with the production
 * service implementations and {@link TestServicesModule} bindings for configurations
 * and stubbed out-of-process collaborators.
 */
@Tag("integration")
public abstract class IntegrationTestBase {

    @RegisterExtension
    static final ContextHolder CONTEXT = new ContextHolder();

    protected DataSource dataSource;
    protected Injector injector;

    @BeforeEach
    void setUpIntegration() throws Exception {
        this.dataSource = MariadbContainerExtension.dataSource(CONTEXT.context);
        MariadbContainerExtension.truncateAll(dataSource);
        this.injector = Guice.createInjector(
                new DatabaseModule(dataSource),
                new TestServicesModule()
        );
    }

    /**
     * Drop the economy-schema V20 ChestShop market foreign keys
     * ({@code fk_cs_sale_*} / {@code fk_cs_shop_*}) if present. The market /
     * sales-query ITs exercise the mapper SQL with synthetic txn/account/firm
     * ids rather than seeding real ledger/accounts/firm rows, so FK enforcement
     * on those two analytics tables is dropped for those tests. Best-effort and
     * idempotent — a missing constraint is ignored.
     */
    protected void dropChestShopMarketForeignKeys() {
        String[] drops = {
                "ALTER TABLE chestshop_sale DROP FOREIGN KEY fk_cs_sale_txn",
                "ALTER TABLE chestshop_sale DROP FOREIGN KEY fk_cs_sale_account",
                "ALTER TABLE chestshop_sale DROP FOREIGN KEY fk_cs_sale_firm",
                "ALTER TABLE chestshop_shop DROP FOREIGN KEY fk_cs_shop_account",
                "ALTER TABLE chestshop_shop DROP FOREIGN KEY fk_cs_shop_firm",
        };
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            for (String drop : drops) {
                try {
                    st.execute(drop);
                } catch (SQLException ignore) {
                    // Constraint already absent (dropped by a prior test on the
                    // shared container, or not yet created) — nothing to do.
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to drop ChestShop market foreign keys for test setup", e);
        }
    }

    /** Captures an ExtensionContext so the static container helper can access JUnit's store. */
    static final class ContextHolder
            implements org.junit.jupiter.api.extension.BeforeAllCallback {
        ExtensionContext context;
        @Override public void beforeAll(ExtensionContext ctx) {
            this.context = ctx;
        }
    }
}
