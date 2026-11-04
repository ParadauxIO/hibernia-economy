package io.paradaux.common.flags;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;

/** Read-only persistence adapter for the shared release-control table. */
public final class JdbcFeatureFlagReader {
    private final DataSource dataSource;

    public JdbcFeatureFlagReader(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Map<String, Boolean> read() throws SQLException {
        Map<String, Boolean> values = new HashMap<>();
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("SELECT name, enabled FROM feature_flags")) {
            statement.setQueryTimeout(3);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String key = rows.getString("name");
                    if (key != null && FeatureFlagSnapshot.validKey(key)) {
                        // Do not coerce arbitrary nonzero values to true.
                        values.put(key, "1".equals(rows.getString("enabled")));
                    }
                }
            }
        }
        return values;
    }
}
