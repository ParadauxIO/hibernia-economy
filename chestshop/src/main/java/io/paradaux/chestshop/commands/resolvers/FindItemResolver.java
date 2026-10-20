package io.paradaux.chestshop.commands.resolvers;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.services.FindAliases;
import io.paradaux.chestshop.services.MarketService;
import io.paradaux.hibernia.framework.commander.spi.ParameterResolver;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/** Suggestions use an immutable snapshot; command completion never queries the database. */
@Singleton
public final class FindItemResolver implements ParameterResolver<FindItem> {
    private final JavaPlugin plugin;
    private final MarketService market;
    private final FindAliases aliases;
    private volatile List<String> codes = List.of();

    @Inject
    public FindItemResolver(JavaPlugin plugin, MarketService market, FindAliases aliases) {
        this.plugin = plugin;
        this.market = market;
        this.aliases = aliases;
    }

    public void start() {
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::refresh, 1L, 600L);
    }

    void refresh() {
        try {
            var api = market.shopQuery();
            if (api != null) codes = List.copyOf(api.matchingItemKeys("", Integer.MAX_VALUE));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Cannot refresh finder item suggestions: " + e.getMessage());
        }
    }

    @Override
    public Class<FindItem> type() {
        return FindItem.class;
    }

    @Override
    public Optional<FindItem> resolve(String token, CommandSender sender) {
        return token == null || token.isBlank() ? Optional.empty() : Optional.of(new FindItem(token));
    }

    @Override
    public List<String> suggestions(String prefix, CommandSender sender) {
        String input = prefix.toLowerCase(Locale.ROOT);
        return Stream.concat(codes.stream(), aliases.names().stream())
                .filter(code -> code.toLowerCase(Locale.ROOT).contains(input))
                .distinct()
                .sorted(Comparator.reverseOrder())
                .toList();
    }
}
