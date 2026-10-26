package io.paradaux.business.commands.resolvers;

import io.paradaux.business.model.FirmPlayer;
import io.paradaux.business.services.FirmPlayerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure resolve/suggestions behaviour of {@link FirmPlayerResolver}
 * (business/testing/0004). Driven with a null CommandSender — the resolver is
 * fed entirely by {@link FirmPlayerService}, no Bukkit access.
 */
@ExtendWith(MockitoExtension.class)
class FirmPlayerResolverTest {

    @Mock FirmPlayerService players;

    private FirmPlayerResolver resolver() {
        return new FirmPlayerResolver(players);
    }

    private static FirmPlayer player(UUID uuid, String name) {
        FirmPlayer p = new FirmPlayer();
        p.setPlayerUuid(uuid.toString());
        p.setCurrentName(name);
        return p;
    }

    // ---- resolve --------------------------------------------------------------

    @Test
    void resolve_rejectsBlankAndNull() {
        assertThat(resolver().resolve(null, null)).isEmpty();
        assertThat(resolver().resolve("   ", null)).isEmpty();
        // A blank/null token must never touch the lookup service.
        verify(players, never()).findByUuid(anyString());
        verify(players, never()).findByName(anyString());
        verify(players, never()).searchByPrefix(anyString(), anyInt());
    }

    @Test
    void resolve_byExactUuid_looksUpByUuid() {
        UUID id = UUID.randomUUID();
        FirmPlayer fp = player(id, "Alice");
        when(players.findByUuid(id)).thenReturn(Optional.of(fp));

        assertThat(resolver().resolve(id.toString(), null)).contains(fp);
        // A UUID token short-circuits — no name/prefix path.
        verify(players, never()).findByName(anyString());
        verify(players, never()).searchByPrefix(anyString(), anyInt());
    }

    @Test
    void resolve_byCompact32CharUuid_isNormalisedAndLookedUp() {
        UUID id = UUID.randomUUID();
        String compact = id.toString().replace("-", "");
        FirmPlayer fp = player(id, "Alice");
        when(players.findByUuid(id)).thenReturn(Optional.of(fp));

        assertThat(resolver().resolve(compact, null)).contains(fp);
    }

    @Test
    void resolve_byExactName_returnsCacheHitWithoutPrefixSearch() {
        FirmPlayer fp = player(UUID.randomUUID(), "Bob");
        when(players.findByName("Bob")).thenReturn(Optional.of(fp));

        assertThat(resolver().resolve("Bob", null)).contains(fp);
        verify(players, never()).searchByPrefix(anyString(), anyInt());
    }

    @Test
    void resolve_byUnambiguousPrefix_returnsTheSingleMatch() {
        FirmPlayer fp = player(UUID.randomUUID(), "Charlie");
        when(players.findByName("Char")).thenReturn(Optional.empty());
        when(players.searchByPrefix("Char", 5)).thenReturn(List.of(fp));

        assertThat(resolver().resolve("Char", null)).contains(fp);
    }

    @Test
    void resolve_byAmbiguousPrefix_returnsEmpty() {
        when(players.findByName("Da")).thenReturn(Optional.empty());
        when(players.searchByPrefix("Da", 5)).thenReturn(List.of(
                player(UUID.randomUUID(), "Dave"), player(UUID.randomUUID(), "Daisy")));

        assertThat(resolver().resolve("Da", null)).isEmpty();
    }

    @Test
    void resolve_noMatchAtAll_returnsEmpty() {
        when(players.findByName("Zzz")).thenReturn(Optional.empty());
        when(players.searchByPrefix("Zzz", 5)).thenReturn(List.of());

        assertThat(resolver().resolve("Zzz", null)).isEmpty();
    }

    // ---- suggestions ----------------------------------------------------------

    @Test
    void suggestions_returnDistinctCurrentNamesFromPrefixSearch() {
        when(players.searchByPrefix("a", 20)).thenReturn(List.of(
                player(UUID.randomUUID(), "Alice"),
                player(UUID.randomUUID(), "Alice"), // duplicate name → collapsed
                player(UUID.randomUUID(), "Anna")));

        assertThat(resolver().suggestions("a", null)).containsExactly("Alice", "Anna");
    }

    @Test
    void suggestions_nullPrefix_searchesEmptyString() {
        when(players.searchByPrefix("", 20)).thenReturn(List.of(player(UUID.randomUUID(), "Eve")));

        assertThat(resolver().suggestions(null, null)).containsExactly("Eve");
    }
}
