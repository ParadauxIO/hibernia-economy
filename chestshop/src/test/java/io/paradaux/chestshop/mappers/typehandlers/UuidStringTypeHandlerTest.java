package io.paradaux.chestshop.mappers.typehandlers;

import org.junit.jupiter.api.Test;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link UuidStringTypeHandler}: it must persist UUIDs as their canonical
 * string form and read that same VARCHAR back, mapping SQL {@code NULL} to {@code null} — the
 * on-disk format ChestShop's SQLite account store has always used (chestshop/testing/0003).
 */
class UuidStringTypeHandlerTest {

    private final UuidStringTypeHandler handler = new UuidStringTypeHandler();

    @Test
    void setNonNullParameter_writesCanonicalString() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        UUID uuid = UUID.fromString("11111111-2222-3333-4444-555555555555");

        handler.setNonNullParameter(ps, 3, uuid, null);

        verify(ps).setString(3, "11111111-2222-3333-4444-555555555555");
    }

    @Test
    void getNullableResult_byColumnName_parsesString() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        UUID uuid = UUID.randomUUID();
        when(rs.getString("id")).thenReturn(uuid.toString());

        assertThat(handler.getNullableResult(rs, "id")).isEqualTo(uuid);
    }

    @Test
    void getNullableResult_byColumnName_nullYieldsNull() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn(null);

        assertThat(handler.getNullableResult(rs, "id")).isNull();
    }

    @Test
    void getNullableResult_byColumnIndex_parsesString() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        UUID uuid = UUID.randomUUID();
        when(rs.getString(1)).thenReturn(uuid.toString());

        assertThat(handler.getNullableResult(rs, 1)).isEqualTo(uuid);
    }

    @Test
    void getNullableResult_byColumnIndex_nullYieldsNull() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn(null);

        assertThat(handler.getNullableResult(rs, 1)).isNull();
    }

    @Test
    void getNullableResult_fromCallableStatement_parsesString() throws Exception {
        CallableStatement cs = mock(CallableStatement.class);
        UUID uuid = UUID.randomUUID();
        when(cs.getString(2)).thenReturn(uuid.toString());

        assertThat(handler.getNullableResult(cs, 2)).isEqualTo(uuid);
    }

    @Test
    void getNullableResult_fromCallableStatement_nullYieldsNull() throws Exception {
        CallableStatement cs = mock(CallableStatement.class);
        when(cs.getString(2)).thenReturn(null);

        assertThat(handler.getNullableResult(cs, 2)).isNull();
    }
}
