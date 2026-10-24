package io.paradaux.business.mappers;

import io.paradaux.business.model.FirmPlayer;
import io.paradaux.business.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Business reads the player directory ({@code economy_players}) but never writes
 * it — Treasury's login listener owns the writes (PAR-35). These tests seed rows
 * directly via JDBC and exercise the read queries.
 */
class FirmPlayerMapperIT extends IntegrationTestBase {

    private FirmPlayerMapper mapper;

    @BeforeEach
    void open() {
        mapper = mapper(FirmPlayerMapper.class);
    }

    @Test
    void getByUuid_returnsSeededRow() throws Exception {
        UUID id = UUID.randomUUID();
        insertPlayer(id, "Alice");

        FirmPlayer p = mapper.getByUuid(id.toString());
        assertThat(p).isNotNull();
        assertThat(p.getCurrentName()).isEqualTo("Alice");
        assertThat(p.getNameLower()).isEqualTo("alice");
    }

    @Test
    void getByName_isCaseInsensitive() throws Exception {
        insertPlayer(UUID.randomUUID(), "Alice");
        assertThat(mapper.getByName("alice")).isNotNull();
        assertThat(mapper.getByName("ALICE")).isNotNull();
        assertThat(mapper.getByName("Alice")).isNotNull();
        assertThat(mapper.getByName("Bob")).isNull();
    }

    @Test
    void searchByPrefix_returnsMatches() throws Exception {
        insertPlayer(UUID.randomUUID(), "Alice");
        insertPlayer(UUID.randomUUID(), "Alex");
        insertPlayer(UUID.randomUUID(), "Bob");

        List<FirmPlayer> hits = mapper.searchByPrefix("al", 10);
        assertThat(hits).extracting(FirmPlayer::getCurrentName)
                .containsExactlyInAnyOrder("Alice", "Alex");
    }
}
