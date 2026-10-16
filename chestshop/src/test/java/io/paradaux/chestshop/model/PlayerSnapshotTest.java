package io.paradaux.chestshop.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlayerSnapshotTest {

    @Mock
    private Player player;

    @Test
    void playerConstructor_capturesTheIdAndName() {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn("Notch");

        PlayerSnapshot dto = new PlayerSnapshot(player);

        assertThat(dto.getUniqueId()).isEqualTo(id);
        assertThat(dto.getName()).isEqualTo("Notch");
    }
}
