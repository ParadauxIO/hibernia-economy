package io.paradaux.business.commands.resolvers;

import io.paradaux.business.utils.Suggestions;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.hibernia.framework.commander.spi.ParameterResolver;
import io.paradaux.business.services.FirmSuggestionCache;
import io.paradaux.business.services.OnlineRosterCache;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Optional;

/**
 * Resolves an {@link OnlineFirmName} argument: accepts any non-blank token (existence
 * is validated downstream), and tab-completes from firms that have an employee or
 * proprietor currently online — the union of each online player's firms. PAR-13.
 *
 * <p>{@code suggestions()} runs on a Netty thread and reads the online roster from a
 * service-managed, lock-free {@link OnlineRosterCache} (maintained on the main thread
 * by {@code OnlineRosterListener}); it never touches live Bukkit state.
 * (business/plugin-architecture/0003)
 */
@Singleton
public final class OnlineFirmNameResolver implements ParameterResolver<OnlineFirmName> {

    private final FirmSuggestionCache cache;
    private final OnlineRosterCache roster;

    @Inject
    public OnlineFirmNameResolver(FirmSuggestionCache cache, OnlineRosterCache roster) {
        this.cache = cache;
        this.roster = roster;
    }

    @Override
    public Class<OnlineFirmName> type() {
        return OnlineFirmName.class;
    }

    @Override
    public Optional<OnlineFirmName> resolve(String token, CommandSender sender) {
        return (token == null || token.isBlank()) ? Optional.empty() : Optional.of(new OnlineFirmName(token.trim()));
    }

    @Override
    public List<String> suggestions(String prefix, CommandSender sender) {
        // Read the online roster from the service-managed, lock-free cache — never
        // Bukkit.getOnlinePlayers() (this runs off the main thread).
        return Suggestions.match(roster.onlineFirmNames(cache), prefix, 20);
    }
}
