package io.paradaux.common.flags;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.*;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class JdbcFeatureFlagReaderTest {
    @Test
    void readsOnlyValidKeysAndExactEnabledValuesAndClosesResources() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT name, enabled FROM feature_flags"))
                .thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, true, true, true, true, true, false);
        when(rows.getString("name")).thenReturn(null, "INVALID", "test.on", "test.off", "test.bad", "test.null");
        when(rows.getString("enabled")).thenReturn("1", "0", "2", null);
        assertEquals(
                Map.of("test.on", true, "test.off", false, "test.bad", false, "test.null", false),
                new JdbcFeatureFlagReader(source).read());
        verify(statement).setQueryTimeout(3);
        verify(rows).close();
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void readFailureDoesNotReturnPartialStateAndClosesResources() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT name, enabled FROM feature_flags"))
                .thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true).thenThrow(new SQLException("read failed"));
        when(rows.getString("name")).thenReturn("test.on");
        when(rows.getString("enabled")).thenReturn("1");
        assertThrows(SQLException.class, () -> new JdbcFeatureFlagReader(source).read());
        verify(rows).close();
        verify(statement).close();
        verify(connection).close();
    }
}
