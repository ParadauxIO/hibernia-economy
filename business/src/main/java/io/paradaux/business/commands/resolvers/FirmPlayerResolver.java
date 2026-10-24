package io.paradaux.business.commands.resolvers;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.hibernia.framework.commander.spi.ParameterResolver;
import io.paradaux.business.model.FirmPlayer;
import io.paradaux.business.services.FirmPlayerService;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Singleton
public final class FirmPlayerResolver implements ParameterResolver<FirmPlayer> {

    private final FirmPlayerService players;

    @Inject
    public FirmPlayerResolver(FirmPlayerService players) {
        this.players = players;
    }

    @Override
    public Class<FirmPlayer> type() {
        return FirmPlayer.class;
    }

    @Override
    public Optional<FirmPlayer> resolve(String token, CommandSender sender) {
        if (token == null || token.isBlank()) return Optional.empty();

        // 1) UUID exact
        UUID asUuid = tryParseUuid(token);
        if (asUuid != null) {
            return players.findByUuid(asUuid);
        }

        // 2) Exact (case-insensitive) name match if present in cache
        Optional<FirmPlayer> byExactName = players.findByName(token);
        if (byExactName.isPresent()) return byExactName;

        // 3) Prefix search – only accept if it’s unambiguous
        List<FirmPlayer> matches = players.searchByPrefix(token, 5);
        if (matches.size() == 1) {
            return Optional.of(matches.get(0));
        }

        // ambiguous or no match
        return Optional.empty();
    }

    @Override
    public List<String> suggestions(String prefix, CommandSender sender) {
        String p = (prefix == null) ? "" : prefix.trim();
        // Return last-known names for tab completion
        return players.searchByPrefix(p, 20).stream()
                .map(FirmPlayer::getCurrentName)
                .distinct()
                .toList();
    }

    private static UUID tryParseUuid(String s) {
        try {
            String norm = s.trim();
            // Support compact 32-char UUID too, because players paste weird stuff
            if (norm.length() == 32) {
                norm = norm.substring(0, 8) + "-" + norm.substring(8, 12) + "-" +
                        norm.substring(12, 16) + "-" + norm.substring(16, 20) + "-" +
                        norm.substring(20);
            }
            return UUID.fromString(norm.toLowerCase(Locale.ROOT));
        } catch (Exception ignored) {
            return null;
        }
    }
}
