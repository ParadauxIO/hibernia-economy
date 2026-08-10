package io.paradaux.business.commands.resolvers;

import io.paradaux.business.utils.Suggestions;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.hibernia.framework.commander.spi.ParameterResolver;
import io.paradaux.business.services.FirmSuggestionCache;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

/**
 * Resolves a {@link FirmName} argument: accepts any non-blank token (existence is
 * validated downstream by the command), and tab-completes from the sender's own
 * firms (owned or employed). Console falls back to all active firms. PAR-13.
 */
@Singleton
public final class FirmNameResolver implements ParameterResolver<FirmName> {

    private final FirmSuggestionCache cache;

    @Inject
    public FirmNameResolver(FirmSuggestionCache cache) {
        this.cache = cache;
    }

    @Override
    public Class<FirmName> type() {
        return FirmName.class;
    }

    @Override
    public Optional<FirmName> resolve(String token, CommandSender sender) {
        return (token == null || token.isBlank()) ? Optional.empty() : Optional.of(new FirmName(token.trim()));
    }

    @Override
    public List<String> suggestions(String prefix, CommandSender sender) {
        if (sender instanceof Player p) {
            return Suggestions.match(cache.playerFirmNames(p.getUniqueId()), prefix, 20);
        }
        return Suggestions.match(cache.activeFirmNames(), prefix, 20);
    }
}
