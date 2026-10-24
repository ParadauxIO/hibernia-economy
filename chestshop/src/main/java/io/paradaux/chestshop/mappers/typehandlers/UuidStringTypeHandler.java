package io.paradaux.chestshop.mappers.typehandlers;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Maps {@link UUID} to/from a {@code VARCHAR} column holding the UUID's canonical string
 * form. ChestShop's SQLite account store has always stored UUIDs as text (that's how the
 * old ORMlite {@code UUID} persister wrote them), so this preserves the on-disk format
 * exactly — unlike the shared-MariaDB plugins, which store UUIDs as {@code BINARY(16)}.
 */
@MappedTypes(UUID.class)
public class UuidStringTypeHandler extends BaseTypeHandler<UUID> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, UUID parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, parameter.toString());
    }

    @Override
    public UUID getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toUuid(rs.getString(columnName));
    }

    @Override
    public UUID getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toUuid(rs.getString(columnIndex));
    }

    @Override
    public UUID getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toUuid(cs.getString(columnIndex));
    }

    private static UUID toUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
