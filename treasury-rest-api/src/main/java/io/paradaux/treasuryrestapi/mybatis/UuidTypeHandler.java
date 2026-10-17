package io.paradaux.treasuryrestapi.mybatis;

import io.paradaux.common.UuidBin;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Converts between Java {@link UUID} and MariaDB {@code BINARY(16)} columns.
 * Delegates the byte layout to {@link UuidBin} — the single source of truth
 * (ADT-22 / ADT-184) shared with the plugins — rather than hand-rolling its own
 * ByteBuffer conversion, so every reader/writer of the BINARY(16) account columns
 * agrees on the encoding and the null/length handling. Registered automatically
 * via mybatis.type-handlers-package in application.yaml.
 */
@MappedTypes(UUID.class)
@MappedJdbcTypes(JdbcType.BINARY)
public class UuidTypeHandler extends BaseTypeHandler<UUID> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, UUID parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setBytes(i, UuidBin.toBytes(parameter));
    }

    @Override
    public UUID getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return UuidBin.fromBytes(rs.getBytes(columnName));
    }

    @Override
    public UUID getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return UuidBin.fromBytes(rs.getBytes(columnIndex));
    }

    @Override
    public UUID getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return UuidBin.fromBytes(cs.getBytes(columnIndex));
    }
}
