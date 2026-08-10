package io.paradaux.business.services;

import java.util.Set;
import java.util.UUID;

/**
 * Lock-free roster of the UUIDs currently online, maintained by a DI-managed
 * join/quit listener ({@link io.paradaux.business.listeners.OnlineRosterListener})
 * on the main thread and read — without touching live Bukkit state — off the
 * suggestion thread.
 *
 * <p>{@code OnlineFirmNameResolver} runs its {@code suggestions()} on a Netty
 * thread and must never call {@code Bukkit.getOnlinePlayers()} / player accessors
 * off the main thread (Bukkit collections are not thread-safe). This cache owns
 * that roster instead: the listener {@link #add}/{@link #remove}s UUIDs, and the
 * resolver composes online firm names via {@link #onlineFirmNames(FirmSuggestionCache)},
 * which iterates a lock-free snapshot and delegates the (already-cached) per-player
 * firm-name lookup to {@link FirmSuggestionCache}. UUID-keyed throughout — no name
 * resolution off-thread. (business/plugin-architecture/0003)
 */
public interface OnlineRosterCache {

    /** Record a player as online. Called on join from the main thread. */
    void add(UUID playerId);

    /** Record a player as no longer online. Called on quit from the main thread. */
    void remove(UUID playerId);

    /** A lock-free snapshot of the online roster. */
    Set<UUID> snapshot();

    /**
     * The union of firm display names across every currently-online player, drawn
     * from {@code cache} (per-player, TTL-backed). Iterates a lock-free view of the
     * roster; a concurrent join/quit cannot break iteration.
     */
    Set<String> onlineFirmNames(FirmSuggestionCache cache);
}
