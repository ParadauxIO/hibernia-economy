package io.paradaux.treasury.services.cache;

import com.google.inject.Singleton;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A lock-free, UUID-keyed mirror of the currently online players' names.
 *
 * <p>Tab-completion for {@code /pay} runs on the command framework's suggestion
 * thread, which is <em>not</em> the main server thread. Reading live Bukkit state
 * ({@code Bukkit.getOnlinePlayers()} / {@code Bukkit.getPlayer(...)}) off the main
 * thread is unsafe, so this roster is maintained on the main thread by a join/quit
 * listener ({@code OnlinePlayerRosterListener}) and read lock-free from the resolver.
 *
 * <p>The map is keyed by UUID (the stable identity) with the display name as the
 * value, so a name change on reconnect replaces cleanly rather than orphaning a
 * stale entry. {@link #resolveByName(String)} does an exact, case-insensitive name
 * lookup; {@link #suggestions(String, int)} returns prefix matches for completion.
 */
@Singleton
public class OnlinePlayerRoster {

    /** UUID → current display name. Concurrent so writes on the main thread are
     *  visible to reads on the suggestion thread without locking. */
    private final Map<UUID, String> namesByUuid = new ConcurrentHashMap<>();

    /** Records (or refreshes) an online player. Called on join from the main thread. */
    public void add(UUID uuid, String name) {
        if (uuid == null || name == null) return;
        namesByUuid.put(uuid, name);
    }

    /** Drops a player from the roster. Called on quit from the main thread. */
    public void remove(UUID uuid) {
        if (uuid == null) return;
        namesByUuid.remove(uuid);
    }

    /** The name currently associated with {@code uuid}, if online. */
    public Optional<String> nameOf(UUID uuid) {
        if (uuid == null) return Optional.empty();
        return Optional.ofNullable(namesByUuid.get(uuid));
    }

    /**
     * Resolves an online player by exact (case-insensitive) name.
     *
     * <p>Minecraft names are unique among online players, so at most one entry can
     * match. Returns empty if no online player has that name.
     */
    public Optional<UUID> resolveByName(String name) {
        if (name == null) return Optional.empty();
        String target = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<UUID, String> e : namesByUuid.entrySet()) {
            if (e.getValue().toLowerCase(Locale.ROOT).equals(target)) {
                return Optional.of(e.getKey());
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves an online player by a name prefix, but only when the match is
     * unambiguous. Returns the UUID iff exactly one online player's name starts with
     * {@code prefix} (case-insensitive); returns empty when zero or more than one
     * match, so an ambiguous prefix is <em>rejected</em> rather than silently binding
     * to an arbitrary player. An exact full-name match always wins over longer names
     * that merely share the prefix.
     */
    public Optional<UUID> resolveByPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) return Optional.empty();
        String p = prefix.toLowerCase(Locale.ROOT);
        UUID prefixMatch = null;
        boolean prefixAmbiguous = false;
        for (Map.Entry<UUID, String> e : namesByUuid.entrySet()) {
            String lower = e.getValue().toLowerCase(Locale.ROOT);
            if (lower.equals(p)) {
                // Exact match is unique and authoritative.
                return Optional.of(e.getKey());
            }
            if (lower.startsWith(p)) {
                if (prefixMatch == null) {
                    prefixMatch = e.getKey();
                } else {
                    prefixAmbiguous = true;
                }
            }
        }
        if (prefixAmbiguous) return Optional.empty();
        return Optional.ofNullable(prefixMatch);
    }

    /**
     * Online player names whose name starts with {@code prefix} (case-insensitive),
     * capped at {@code limit}. Order is unspecified.
     */
    public List<String> suggestions(String prefix, int limit) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String name : namesByUuid.values()) {
            if (name.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(name);
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    /** Number of players currently in the roster. */
    public int size() {
        return namesByUuid.size();
    }
}
