package io.paradaux.chestshop.mappers.typehandlers;

import org.junit.jupiter.api.Test;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link DateLongTypeHandler}: it must persist {@link Date} as epoch-millis in
 * a BIGINT column and read it back, mapping SQL {@code NULL} (a {@code getLong} zero flagged by
 * {@code wasNull}) to {@code null} — the {@code lastSeen} format ChestShop's SQLite store has
 * always used (chestshop/testing/0003).
 */
class DateLongTypeHandlerTest {

    private final DateLongTypeHandler handler = new DateLongTypeHandler();

    @Test
    void setNonNullParameter_writesEpochMillis() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);

        handler.setNonNullParameter(ps, 4, new Date(1_700_000_000_000L), null);

        verify(ps).setLong(4, 1_700_000_000_000L);
    }

    @Test
    void getNullableResult_byColumnName_readsEpochMillis() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("lastSeen")).thenReturn(1_700_000_000_000L);
        when(rs.wasNull()).thenReturn(false);

        assertThat(handler.getNullableResult(rs, "lastSeen")).isEqualTo(new Date(1_700_000_000_000L));
    }

    @Test
    void getNullableResult_byColumnName_nullYieldsNull() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("lastSeen")).thenReturn(0L);
        when(rs.wasNull()).thenReturn(true);

        assertThat(handler.getNullableResult(rs, "lastSeen")).isNull();
    }

    @Test
    void getNullableResult_byColumnIndex_readsEpochMillis() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong(2)).thenReturn(42L);
        when(rs.wasNull()).thenReturn(false);

        assertThat(handler.getNullableResult(rs, 2)).isEqualTo(new Date(42L));
    }

    @Test
    void getNullableResult_byColumnIndex_nullYieldsNull() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong(2)).thenReturn(0L);
        when(rs.wasNull()).thenReturn(true);

        assertThat(handler.getNullableResult(rs, 2)).isNull();
    }

    @Test
    void getNullableResult_fromCallableStatement_readsEpochMillis() throws Exception {
        CallableStatement cs = mock(CallableStatement.class);
        when(cs.getLong(5)).thenReturn(99L);
        when(cs.wasNull()).thenReturn(false);

        assertThat(handler.getNullableResult(cs, 5)).isEqualTo(new Date(99L));
    }

    @Test
    void getNullableResult_fromCallableStatement_nullYieldsNull() throws Exception {
        CallableStatement cs = mock(CallableStatement.class);
        when(cs.getLong(5)).thenReturn(0L);
        when(cs.wasNull()).thenReturn(true);

        assertThat(handler.getNullableResult(cs, 5)).isNull();
    }
}
