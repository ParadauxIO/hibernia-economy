package io.paradaux.common;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class NameReservationErrorsTest {
    @Test
    void recognizesOnlyTheDatabaseSignalsAndDoesNotExposeSql() {
        var cause = new SQLException("GLOBAL_NAME_CONFLICT: private SQL and names", "45000", 1644);
        String message =
                NameReservationErrors.userMessage(new RuntimeException(cause)).orElseThrow();
        assertTrue(message.contains("reserved"));
        assertFalse(message.contains("private SQL"));
        assertTrue(NameReservationErrors.userMessage(new SQLException("GLOBAL_NAME_CONFLICT:", "42000"))
                .isEmpty());
        assertTrue(NameReservationErrors.userMessage(new RuntimeException("GLOBAL_NAME_CONFLICT:"))
                .isEmpty());
        assertTrue(NameReservationErrors.userMessage(new SQLException("unrelated error", "45000"))
                .isEmpty());
        assertTrue(NameReservationErrors.userMessage(
                        new SQLException("Duplicate key uq_firm_reserved_display_name", "23000", 1062))
                .isPresent());
        assertTrue(NameReservationErrors.userMessage(new SQLException("Duplicate key another_index", "23000", 1062))
                .isEmpty());
        assertTrue(NameReservationErrors.userMessage(new SQLException("GLOBAL_NAME_INVALID:", "45000"))
                .orElseThrow()
                .contains("nonblank"));
    }

    @Test
    void recognizesShopAliasConstraintButRequiresDuplicateKeyErrorCode() {
        assertTrue(
                NameReservationErrors.userMessage(new SQLException("Duplicate key uq_firm_shop_alias", "23000", 1062))
                        .isPresent());
        for (String constraint : new String[] {"uq_firm_shop_alias", "uq_firm_reserved_display_name"}) {
            assertTrue(NameReservationErrors.userMessage(new SQLException(constraint, "23000", 1048))
                    .isEmpty());
        }
    }

    @Test
    void ignoresSqlErrorsWithoutMessages() {
        assertTrue(NameReservationErrors.userMessage(new SQLException(null, "23000", 1062))
                .isEmpty());
        assertTrue(NameReservationErrors.userMessage(new SQLException(null, "45000", 1644))
                .isEmpty());
    }

    @Test
    void boundsCauseTraversalWithoutMissingTheLastAllowedCause() {
        Throwable withinLimit = new SQLException("GLOBAL_NAME_CONFLICT:", "45000", 1644);
        for (int i = 0; i < 31; i++) withinLimit = new RuntimeException(withinLimit);
        assertTrue(NameReservationErrors.userMessage(withinLimit).isPresent());
        assertTrue(NameReservationErrors.userMessage(new RuntimeException(withinLimit))
                .isEmpty());
    }
}
