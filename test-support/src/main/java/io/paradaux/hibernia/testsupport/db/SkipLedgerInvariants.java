package io.paradaux.hibernia.testsupport.db;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opts a DB-backed test (method or whole class) out of the post-test ledger
 * invariant check that {@link TestDatabaseExtension} otherwise runs after every
 * test. Only for tests that corrupt the ledger <em>on purpose</em> — e.g. to prove a
 * trigger gap exists or that the check itself detects drift. The reason is
 * mandatory so the exemption is reviewable where it is declared.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface SkipLedgerInvariants {

    /** Why this test leaves the ledger inconsistent. Must not be blank. */
    String value();
}
