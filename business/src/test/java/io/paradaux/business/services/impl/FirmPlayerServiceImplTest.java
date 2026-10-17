package io.paradaux.business.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.paradaux.business.mappers.FirmPlayerMapper;
import io.paradaux.business.model.FirmPlayer;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FirmPlayerServiceImplTest {

    @Mock
    FirmPlayerMapper mapper;

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

    @ParameterizedTest
    @CsvSource({
        // requested limit -> limit passed to the mapper (1..100 kept, else default 20)
        "-1, 20",
        "0, 20",
        "1, 1",
        "50, 50",
        "100, 100",
        "101, 20",
        "200, 20",
    })
    void searchByPrefix_passesTheClampedLimitAndTrimmedPrefixToTheMapper(int requested, int expected) {
        List<FirmPlayer> rows = List.of(new FirmPlayer());
        when(mapper.searchByPrefix("ali", expected)).thenReturn(rows);

        assertThat(svc.searchByPrefix("  ali  ", requested)).isSameAs(rows);

        ArgumentCaptor<String> prefix = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(mapper).searchByPrefix(prefix.capture(), limit.capture());
        assertThat(prefix.getValue()).isEqualTo("ali");
        assertThat(limit.getValue()).isEqualTo(expected);
    }

    @Test
    void searchByPrefix_nullPrefixBecomesEmpty() {
        List<FirmPlayer> rows = List.of(new FirmPlayer());
        when(mapper.searchByPrefix("", 20)).thenReturn(rows);

        assertThat(svc.searchByPrefix(null, 0)).isSameAs(rows);
        verify(mapper).searchByPrefix("", 20);
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
