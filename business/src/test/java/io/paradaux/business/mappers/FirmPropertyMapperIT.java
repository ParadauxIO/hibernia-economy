package io.paradaux.business.mappers;

import static org.assertj.core.api.Assertions.assertThat;

import io.paradaux.business.model.FirmProperty;
import io.paradaux.business.model.internal.Firm;
import io.paradaux.business.testsupport.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FirmPropertyMapperIT extends IntegrationTestBase {

    private FirmPropertyMapper mapper;
    private int firmId;

    @BeforeEach
    void seed() {
        mapper = mapper(FirmPropertyMapper.class);
        FirmMapper firms = mapper(FirmMapper.class);

        Firm f = new Firm();
        f.setDisplayName("Acme");
        f.setProprietorUuid(UUID.randomUUID().toString());
        firms.createFirm(f);
        firmId = f.getFirmId();
    }

    @Test
    void setProperty_insertsRow() {
        mapper.setProperty(firmId, "tax-exempt", "true", "BOOLEAN");
        FirmProperty p = mapper.getProperty(firmId, "tax-exempt");
        assertThat(p).isNotNull();
        assertThat(p.getValue()).isEqualTo("true");
        assertThat(p.getType()).isEqualTo("BOOLEAN");
    }

    @Test
    void setProperty_upsertsExistingRow() {
        mapper.setProperty(firmId, "k", "1", "INTEGER");
        mapper.setProperty(firmId, "k", "2", "INTEGER");
        assertThat(mapper.getProperty(firmId, "k").getValue()).isEqualTo("2");
    }

    @Test
    void setProperty_canFlipType() {
        mapper.setProperty(firmId, "k", "1", "INTEGER");
        mapper.setProperty(firmId, "k", "true", "BOOLEAN");
        FirmProperty p = mapper.getProperty(firmId, "k");
        assertThat(p.getType()).isEqualTo("BOOLEAN");
        assertThat(p.getValue()).isEqualTo("true");
    }

    @Test
    void getProperty_missingReturnsNull() {
        assertThat(mapper.getProperty(firmId, "nope")).isNull();
    }

    @Test
    void deleteProperty_removesRow() {
        mapper.setProperty(firmId, "k", "v", "STRING");
        mapper.deleteProperty(firmId, "k");
        assertThat(mapper.getProperty(firmId, "k")).isNull();
    }
}
