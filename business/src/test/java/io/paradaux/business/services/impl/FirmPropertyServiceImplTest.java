package io.paradaux.business.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.paradaux.business.internal.model.FirmProperty;
import io.paradaux.business.mappers.FirmPropertyMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FirmPropertyServiceImplTest {

    @Mock
    FirmPropertyMapper mapper;

    private FirmPropertyServiceImpl svc;

    @BeforeEach
    void setUp() {
        svc = new FirmPropertyServiceImpl(mapper);
    }

    private FirmProperty prop(String type, String value) {
        FirmProperty p = new FirmProperty();
        p.setFirmId(1);
        p.setKey("k");
        p.setValue(value);
        p.setType(type);
        return p;
    }

    // ---------- getString ----------

    @Test
    void getString_missingReturnsEmpty() {
        when(mapper.getProperty(1, "k")).thenReturn(null);
        assertThat(svc.getString(1, "k")).isEmpty();
    }

    @Test
    void getString_wrongTypeReturnsEmpty() {
        when(mapper.getProperty(1, "k")).thenReturn(prop("INTEGER", "5"));
        assertThat(svc.getString(1, "k")).isEmpty();
    }

    @Test
    void getString_returnsValue() {
        when(mapper.getProperty(1, "k")).thenReturn(prop("STRING", "hi"));
        assertThat(svc.getString(1, "k")).contains("hi");
    }

    // ---------- getInteger ----------

    @Test
    void getInteger_returnsValue() {
        when(mapper.getProperty(1, "k")).thenReturn(prop("INTEGER", "42"));
        assertThat(svc.getInteger(1, "k")).contains(42);
    }

    @Test
    void getInteger_invalidValueReturnsEmpty() {
        when(mapper.getProperty(1, "k")).thenReturn(prop("INTEGER", "nan"));
        assertThat(svc.getInteger(1, "k")).isEmpty();
    }

    @Test
    void getInteger_wrongTypeReturnsEmpty() {
        when(mapper.getProperty(1, "k")).thenReturn(prop("STRING", "42"));
        assertThat(svc.getInteger(1, "k")).isEmpty();
    }

    // ---------- getBigDecimal ----------

    @Test
    void getBigDecimal_returnsValue() {
        when(mapper.getProperty(1, "k")).thenReturn(prop("BIGDECIMAL", "10.50"));
        assertThat(svc.getBigDecimal(1, "k")).contains(new BigDecimal("10.50"));
    }

    @Test
    void getBigDecimal_invalidValueReturnsEmpty() {
        when(mapper.getProperty(1, "k")).thenReturn(prop("BIGDECIMAL", "nope"));
        assertThat(svc.getBigDecimal(1, "k")).isEmpty();
    }

    @Test
    void getBigDecimal_wrongTypeReturnsEmpty() {
        when(mapper.getProperty(1, "k")).thenReturn(prop("INTEGER", "5"));
        assertThat(svc.getBigDecimal(1, "k")).isEmpty();
    }

    // ---------- getBoolean ----------

    @Test
    void getBoolean_returnsValue() {
        when(mapper.getProperty(1, "k")).thenReturn(prop("BOOLEAN", "true"));
        assertThat(svc.getBoolean(1, "k")).contains(true);
        when(mapper.getProperty(1, "k")).thenReturn(prop("BOOLEAN", "false"));
        assertThat(svc.getBoolean(1, "k")).contains(false);
    }

    @Test
    void getBoolean_wrongTypeReturnsEmpty() {
        when(mapper.getProperty(1, "k")).thenReturn(prop("STRING", "true"));
        assertThat(svc.getBoolean(1, "k")).isEmpty();
    }

    @Test
    void getBoolean_missingReturnsEmpty() {
        when(mapper.getProperty(1, "k")).thenReturn(null);
        assertThat(svc.getBoolean(1, "k")).isEmpty();
    }

    // ---------- setters ----------

    @Test
    void setString_storesAsString() {
        svc.setString(1, "k", "hi");
        verify(mapper).setProperty(1, "k", "hi", "STRING");
    }

    @Test
    void setInteger_storesAsString() {
        svc.setInteger(1, "k", 42);
        verify(mapper).setProperty(1, "k", "42", "INTEGER");
    }

    @Test
    void setBigDecimal_storesPlainString() {
        svc.setBigDecimal(1, "k", new BigDecimal("100.50"));
        verify(mapper).setProperty(1, "k", "100.50", "BIGDECIMAL");
    }

    @Test
    void setBoolean_storesAsString() {
        svc.setBoolean(1, "k", true);
        verify(mapper).setProperty(1, "k", "true", "BOOLEAN");
    }

    @Test
    void delete_callsMapper() {
        svc.delete(1, "k");
        verify(mapper).deleteProperty(1, "k");
    }
}
