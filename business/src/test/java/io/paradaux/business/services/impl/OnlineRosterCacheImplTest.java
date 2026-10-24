package io.paradaux.business.services.impl;

import io.paradaux.business.model.Firm;
import io.paradaux.business.services.FirmService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Pins the lock-free online-roster cache that backs {@code OnlineFirmNameResolver}
 * so the resolver never touches live Bukkit state off the main thread
 * (business/plugin-architecture/0003).
 */
@ExtendWith(MockitoExtension.class)
class OnlineRosterCacheImplTest {

    @Mock FirmService firms;

    private static Firm firm(String displayName) {
        Firm f = new Firm();
        f.setDisplayName(displayName);
        return f;
    }

    private FirmSuggestionCacheImpl suggestionCache() {
        return new FirmSuggestionCacheImpl(firms);
    }

    @Test
    void snapshot_reflectsAddAndRemove() {
        OnlineRosterCacheImpl roster = new OnlineRosterCacheImpl();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        assertThat(roster.snapshot()).isEmpty();

        roster.add(a);
        roster.add(b);
        assertThat(roster.snapshot()).containsExactlyInAnyOrder(a, b);

        roster.remove(a);
        assertThat(roster.snapshot()).containsExactly(b);
    }

    @Test
    void add_ignoresNull() {
        OnlineRosterCacheImpl roster = new OnlineRosterCacheImpl();
        roster.add(null);
        roster.remove(null);
        assertThat(roster.snapshot()).isEmpty();
    }

    @Test
    void onlineFirmNames_unionsPerPlayerFirmsAcrossRoster() {
        OnlineRosterCacheImpl roster = new OnlineRosterCacheImpl();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        roster.add(a);
        roster.add(b);

        when(firms.listOwnedOrMemberFirms(a)).thenReturn(List.of(firm("Acme"), firm("Shared")));
        when(firms.listOwnedOrMemberFirms(b)).thenReturn(List.of(firm("Globex"), firm("Shared")));

        assertThat(roster.onlineFirmNames(suggestionCache()))
                .containsExactlyInAnyOrder("Acme", "Globex", "Shared");
    }

    @Test
    void onlineFirmNames_emptyWhenNobodyOnline() {
        OnlineRosterCacheImpl roster = new OnlineRosterCacheImpl();
        lenient().when(firms.listOwnedOrMemberFirms(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(firm("Unreachable")));

        assertThat(roster.onlineFirmNames(suggestionCache())).isEmpty();
    }
}
