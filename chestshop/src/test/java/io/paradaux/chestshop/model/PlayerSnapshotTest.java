package io.paradaux.chestshop.model;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class PlayerSnapshotTest {

    @Mock private Player player;

    @Test
    void uuidNameConstructor_storesValuesVerbatim() {
        UUID id = UUID.randomUUID();
        PlayerSnapshot dto = new PlayerSnapshot(id, "Steve");

        assertThat(dto.getUniqueId()).isEqualTo(id);
        assertThat(dto.getName()).isEqualTo("Steve");
    }

    @Test
    void playerConstructor_extractsIdAndNameFromPlayer() {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        lenient().when(player.getUniqueId()).thenReturn(id);
        lenient().when(player.getName()).thenReturn("Notch");

        PlayerSnapshot dto = new PlayerSnapshot(player);

        assertThat(dto.getUniqueId()).isEqualTo(id);
        assertThat(dto.getName()).isEqualTo("Notch");
    }

    @Test
    void setters_overwriteFields() {
        PlayerSnapshot dto = new PlayerSnapshot(UUID.randomUUID(), "old");
        UUID newId = UUID.randomUUID();

        dto.setUniqueId(newId);
        dto.setName("new");

        assertThat(dto.getUniqueId()).isEqualTo(newId);
        assertThat(dto.getName()).isEqualTo("new");
    }
}
