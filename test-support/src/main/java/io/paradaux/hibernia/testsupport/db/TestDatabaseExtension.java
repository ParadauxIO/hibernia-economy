package io.paradaux.hibernia.testsupport.db;

import java.lang.reflect.AnnotatedElement;
import java.util.Optional;
import javax.sql.DataSource;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * The shared MariaDB integration-test harness, parametrised per module by a
 * {@link TestDatabase} (schema name + env-var prefix). Register it as a static field
 * on a module's integration-test base class:
 *
 * <pre>{@code
 * @RegisterExtension
 * static final TestDatabaseExtension DB =
 *         new TestDatabaseExtension(TestDatabase.of("treasury_test", "TREASURY"));
 * }</pre>
 *
 * <p>Per test it:
 * <ol>
 *   <li><b>before</b> — resets the database to its just-migrated state: every
 *       table derived from {@code information_schema} is cleared, migration-seeded
 *       rows are restored (see {@link DatabaseHandle#reset()}). Runs before the base
 *       class's own {@code @BeforeEach}, so fixtures are built on a clean slate.</li>
 *   <li><b>after</b> — runs {@code ops/money-invariants.sql} and fails the test if
 *       the ledger is inconsistent (money created/destroyed, an unbalanced txn, a
 *       materialised balance that drifted from its postings, an illegal negative).
 *       That turns every DB-backed test into a ledger-consistency test for free; a
 *       test that corrupts the ledger on purpose opts out with
 *       {@link SkipLedgerInvariants}. Cost: one aggregate query over the handful of
 *       rows a test writes (low single-digit ms), so it runs per test rather than
 *       per class, which also pins a violation to the exact test that caused it.</li>
 * </ol>
 */
public final class TestDatabaseExtension implements BeforeEachCallback, AfterEachCallback {

    private final TestDatabase spec;

    public TestDatabaseExtension(TestDatabase spec) {
        this.spec = spec;
    }

    public TestDatabase spec() {
        return spec;
    }

    /** The JVM-wide pooled DataSource (booted and migrated on first use). */
    public DataSource dataSource() {
        return TestDatabases.dataSource(spec);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        TestDatabases.reset(spec);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        if (skipReason(context).isPresent()) return;
        LedgerInvariants.assertHold(
                dataSource(),
                context.getDisplayName() + " (" + context.getRequiredTestClass().getSimpleName() + ")");
    }

    private static Optional<String> skipReason(ExtensionContext context) {
        Optional<String> onMethod = reason(context.getTestMethod().orElse(null));
        if (onMethod.isPresent()) return onMethod;
        for (Class<?> c = context.getRequiredTestClass(); c != null; c = c.getEnclosingClass()) {
            Optional<String> onClass = reason(c);
            if (onClass.isPresent()) return onClass;
        }
        return Optional.empty();
    }

    private static Optional<String> reason(@Nullable AnnotatedElement element) {
        if (element == null) return Optional.empty();
        return AnnotationSupport.findAnnotation(element, SkipLedgerInvariants.class)
                .map(skip -> {
                    if (skip.value() == null || skip.value().isBlank()) {
                        throw new IllegalStateException("@SkipLedgerInvariants on " + element
                                + " needs a reason: say why this test leaves the ledger inconsistent");
                    }
                    return skip.value();
                });
    }
}
