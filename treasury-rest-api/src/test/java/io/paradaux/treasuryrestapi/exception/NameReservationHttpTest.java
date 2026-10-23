package io.paradaux.treasuryrestapi.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class NameReservationHttpTest {
    @Test
    void nestedDatabaseSignalReturnsConflictWithoutLeakingQueryDetails() {
        var error = new RuntimeException(new SQLException("GLOBAL_NAME_CONFLICT: private SQL", "45000", 1644));
        var response = new GlobalExceptionHandler().handleUnexpected(error, mock(HttpServletRequest.class));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().error()).isEqualTo("NAME_RESERVED");
        assertThat(response.getBody().message()).contains("another identity").doesNotContain("private SQL");
    }

    @Test
    void unrelatedSqlFailureRemainsAnInternalError() {
        var response = new GlobalExceptionHandler()
                .handleUnexpected(
                        new RuntimeException(new SQLException("connection unavailable", "08001")),
                        mock(HttpServletRequest.class));
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().error()).isEqualTo("INTERNAL_ERROR");
    }
}
