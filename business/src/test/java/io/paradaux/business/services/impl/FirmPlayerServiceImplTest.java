package io.paradaux.business.services.impl;

import io.paradaux.business.mappers.FirmPlayerMapper;
import io.paradaux.business.model.FirmPlayer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FirmPlayerServiceImplTest {

    @Mock FirmPlayerMapper mapper;

    private FirmPlayerServiceImpl svc;
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void setUp() {
        svc = new FirmPlayerServiceImpl(mapper);
        bukkit = Mockito.mockStatic(Bukkit.class);
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    @Test
    void findByUuid_nullReturnsEmpty() {
        assertThat(svc.findByUuid((UUID) null)).isEmpty();
    }

    @Test
    void findByUuid_returnsPlayer() {
        UUID id = UUID.randomUUID();
        FirmPlayer p = new FirmPlayer();
        when(mapper.getByUuid(id.toString())).thenReturn(p);
        assertThat(svc.findByUuid(id)).contains(p);
    }

    @Test
    void findByUuidString_nullReturnsEmpty() {
        assertThat(svc.findByUuid((String) null)).isEmpty();
    }

    @Test
    void findByUuidString_returnsPlayer() {
        FirmPlayer p = new FirmPlayer();
        when(mapper.getByUuid("abc")).thenReturn(p);
        assertThat(svc.findByUuid("abc")).contains(p);
    }

    @Test
    void findByName_blankReturnsEmpty() {
        assertThat(svc.findByName(null)).isEmpty();
        assertThat(svc.findByName("  ")).isEmpty();
    }

    @Test
    void findByName_returnsPlayer() {
        FirmPlayer p = new FirmPlayer();
        when(mapper.getByName("Alice")).thenReturn(p);
        assertThat(svc.findByName("Alice")).contains(p);
    }

    @Test
    void searchByPrefix_clampsLimitsAndTrimsPrefix() {
        when(mapper.searchByPrefix("ali", 20)).thenReturn(List.of(new FirmPlayer()));
        assertThat(svc.searchByPrefix("  ali  ", 0)).hasSize(1);
        assertThat(svc.searchByPrefix("  ali  ", 200)).hasSize(1);
    }

    @Test
    void searchByPrefix_usesGivenLimitWhenInRange() {
        when(mapper.searchByPrefix("ali", 50)).thenReturn(List.of());
        svc.searchByPrefix("ali", 50);
    }

    @Test
    void searchByPrefix_nullPrefixBecomesEmpty() {
        when(mapper.searchByPrefix("", 20)).thenReturn(List.of());
        svc.searchByPrefix(null, 0);
    }

    @Test
    void isOnlineByUuid_truthyWhenBukkitReturnsPlayer() {
        UUID id = UUID.randomUUID();
        Player p = Mockito.mock(Player.class);
        bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(p);
        assertThat(svc.isOnline(id)).isTrue();
    }

    @Test
    void isOnlineByUuid_falseWhenBukkitReturnsNull() {
        UUID id = UUID.randomUUID();
        bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(null);
        assertThat(svc.isOnline(id)).isFalse();
    }

    @Test
    void isOnlineByPlayer_resolvesUniqueId() {
        UUID id = UUID.randomUUID();
        FirmPlayer fp = new FirmPlayer();
        fp.setPlayerUuid(id.toString());
        bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(Mockito.mock(Player.class));
        assertThat(svc.isOnline(fp)).isTrue();
    }

    @Test
    void getPlayer_delegatesToBukkit() {
        UUID id = UUID.randomUUID();
        FirmPlayer fp = new FirmPlayer();
        fp.setPlayerUuid(id.toString());
        Player p = Mockito.mock(Player.class);
        bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(p);
        assertThat(svc.getPlayer(fp)).isSameAs(p);
    }
}
