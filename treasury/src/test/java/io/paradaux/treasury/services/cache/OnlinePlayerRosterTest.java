package io.paradaux.treasury.services.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link OnlinePlayerRoster} — the lock-free online-name mirror
 * that replaced the {@code Bukkit.getOnlinePlayers()} call the {@code /pay} tab
 * completer used to make off the main thread (PAR-0003).
 *
 * <p>Covers the three behaviours the resolver relies on: UUID-keyed lookup, exact
 * name fallback, and ambiguous-prefix rejection.
 */
class OnlinePlayerRosterTest {

    private OnlinePlayerRoster roster;

    @BeforeEach
    void setUp() {
        roster = new OnlinePlayerRoster();
    }

    @Test
    void addThenNameOf_returnsName_uuidKeyed() {
        UUID id = UUID.randomUUID();
        roster.add(id, "Steve");

        assertThat(roster.nameOf(id)).contains("Steve");
        assertThat(roster.size()).isEqualTo(1);
    }

    @Test
    void reconnectUnderNewName_replacesCleanly_noStaleOrphan() {
        UUID id = UUID.randomUUID();
        roster.add(id, "OldName");
        roster.add(id, "NewName");

        // Keyed by UUID: one entry, updated in place — the old name is gone.
        assertThat(roster.size()).isEqualTo(1);
        assertThat(roster.nameOf(id)).contains("NewName");
        assertThat(roster.resolveByName("OldName")).isEmpty();
        assertThat(roster.resolveByName("NewName")).contains(id);
    }

    @Test
    void remove_dropsFromRoster() {
        UUID id = UUID.randomUUID();
        roster.add(id, "Steve");
        roster.remove(id);

        assertThat(roster.nameOf(id)).isEmpty();
        assertThat(roster.size()).isZero();
    }

    @Test
    void resolveByName_isCaseInsensitiveExactMatch() {
        UUID id = UUID.randomUUID();
        roster.add(id, "Steve");

        assertThat(roster.resolveByName("steve")).contains(id);
        assertThat(roster.resolveByName("STEVE")).contains(id);
        // Prefix is NOT an exact-name match.
        assertThat(roster.resolveByName("Stev")).isEmpty();
        assertThat(roster.resolveByName("Unknown")).isEmpty();
    }

    @Test
    void resolveByPrefix_uniqueMatch_resolves() {
        UUID steve = UUID.randomUUID();
        UUID alex = UUID.randomUUID();
        roster.add(steve, "Steve");
        roster.add(alex, "Alex");

        assertThat(roster.resolveByPrefix("Ste")).contains(steve);
        assertThat(roster.resolveByPrefix("Al")).contains(alex);
    }

    @Test
    void resolveByPrefix_ambiguousPrefix_isRejected() {
        UUID steve = UUID.randomUUID();
        UUID steven = UUID.randomUUID();
        roster.add(steve, "Steve");
        roster.add(steven, "Steven");

        // "Stev" matches both — reject rather than bind to an arbitrary one.
        assertThat(roster.resolveByPrefix("Stev")).isEmpty();
    }

    @Test
    void resolveByPrefix_exactFullNameWins_overLongerPrefixSibling() {
        UUID steve = UUID.randomUUID();
        UUID steven = UUID.randomUUID();
        roster.add(steve, "Steve");
        roster.add(steven, "Steven");

        // "Steve" is a full-name match for one player even though "Steven" shares
        // the prefix — the exact match is authoritative, not ambiguous.
        assertThat(roster.resolveByPrefix("Steve")).contains(steve);
    }

    @Test
    void resolveByPrefix_noMatch_empty() {
        roster.add(UUID.randomUUID(), "Steve");
        assertThat(roster.resolveByPrefix("Zed")).isEmpty();
    }

    @Test
    void suggestions_prefixMatch_capped() {
        for (int i = 0; i < 5; i++) {
            roster.add(UUID.randomUUID(), "Player" + i);
        }
        roster.add(UUID.randomUUID(), "Other");

        List<String> hits = roster.suggestions("Play", 3);
        assertThat(hits).hasSize(3).allMatch(n -> n.startsWith("Player"));

        assertThat(roster.suggestions("Oth", 20)).containsExactly("Other");
        assertThat(roster.suggestions("zzz", 20)).isEmpty();
    }

    @Test
    void nullArgs_areIgnored_notStored() {
        roster.add(null, "X");
        roster.add(UUID.randomUUID(), null);
        assertThat(roster.size()).isZero();
        assertThat(roster.nameOf(null)).isEmpty();
        assertThat(roster.resolveByName(null)).isEmpty();
        assertThat(roster.resolveByPrefix(null)).isEmpty();

        Optional<UUID> empty = roster.resolveByPrefix("");
        assertThat(empty).isEmpty();
    }
}
