package io.paradaux.business.services.impl;

import io.paradaux.business.model.Firm;
import io.paradaux.business.services.FirmService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FirmSuggestionCacheImplTest {

    @Mock FirmService firms;

    private final AtomicLong now = new AtomicLong(0L);
    private final LongSupplier clock = now::get;

    private FirmSuggestionCacheImpl cache() {
        return new FirmSuggestionCacheImpl(firms, clock);
    }

    private static Firm firm(String displayName) {
        Firm f = new Firm();
        f.setDisplayName(displayName);
        return f;
    }

    private static Firm archivedFirm(String displayName) {
        Firm f = firm(displayName);
        f.setArchived(true);
        return f;
    }

    // ---- playerFirmNames: caching + TTL ----------------------------------------

    @Test
    void playerFirmNames_returnsDisplayNames() {
        UUID player = UUID.randomUUID();
        when(firms.listOwnedOrMemberFirms(player)).thenReturn(List.of(firm("Acme"), firm("Globex")));

        assertThat(cache().playerFirmNames(player)).containsExactlyInAnyOrder("Acme", "Globex");
    }

    @Test
    void playerFirmNames_cachesWithinTtl_thenRefreshes() {
        UUID player = UUID.randomUUID();
        when(firms.listOwnedOrMemberFirms(player))
                .thenReturn(List.of(firm("Acme")))
                .thenReturn(List.of(firm("Acme"), firm("Globex")));

        FirmSuggestionCacheImpl c = cache();

        // First call populates the cache.
        assertThat(c.playerFirmNames(player)).containsExactly("Acme");
        // Within TTL → served from cache, no second DB hit.
        now.set(FirmSuggestionCacheImpl.TTL_MS);
        assertThat(c.playerFirmNames(player)).containsExactly("Acme");
        verify(firms, times(1)).listOwnedOrMemberFirms(player);

        // Past TTL → refreshed from DB.
        now.set(FirmSuggestionCacheImpl.TTL_MS + 1);
        assertThat(c.playerFirmNames(player)).containsExactlyInAnyOrder("Acme", "Globex");
        verify(firms, times(2)).listOwnedOrMemberFirms(player);
    }

    @Test
    void playerFirmNames_dropsNullDisplayNames() {
        UUID player = UUID.randomUUID();
        when(firms.listOwnedOrMemberFirms(player)).thenReturn(List.of(firm("Acme"), firm(null)));

        assertThat(cache().playerFirmNames(player)).containsExactly("Acme");
    }

    @Test
    void playerFirmNames_dropsDisbandedFirms() {
        UUID player = UUID.randomUUID();
        // A firm the player used to belong to but is now disbanded must not be suggested,
        // since it no longer resolves on command paths.
        when(firms.listOwnedOrMemberFirms(player)).thenReturn(List.of(firm("Acme"), archivedFirm("OldCorp")));

        assertThat(cache().playerFirmNames(player)).containsExactly("Acme");
    }

    // ---- activeFirmNames: caching + TTL ----------------------------------------

    @Test
    void activeFirmNames_cachesWithinTtl_thenRefreshes() {
        when(firms.listAllActiveFirms())
                .thenReturn(List.of(firm("Acme")))
                .thenReturn(List.of(firm("Acme"), firm("Initech")));

        FirmSuggestionCacheImpl c = cache();

        assertThat(c.activeFirmNames()).containsExactly("Acme");
        now.set(FirmSuggestionCacheImpl.TTL_MS);
        assertThat(c.activeFirmNames()).containsExactly("Acme");
        verify(firms, times(1)).listAllActiveFirms();

        now.set(FirmSuggestionCacheImpl.TTL_MS + 1);
        assertThat(c.activeFirmNames()).containsExactlyInAnyOrder("Acme", "Initech");
        verify(firms, times(2)).listAllActiveFirms();
    }
}
