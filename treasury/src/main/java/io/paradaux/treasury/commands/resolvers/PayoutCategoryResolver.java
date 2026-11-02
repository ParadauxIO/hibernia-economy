package io.paradaux.treasury.commands.resolvers;

import io.paradaux.hibernia.framework.commander.spi.ParameterResolver;
import io.paradaux.treasury.model.economy.PayoutCategory;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.command.CommandSender;

/** Reject unknown categories before a payout can reach the ledger. */
public class PayoutCategoryResolver implements ParameterResolver<PayoutCategory> {
    @Override
    public Class<PayoutCategory> type() {
        return PayoutCategory.class;
    }

    @Override
    public Optional<PayoutCategory> resolve(String token, CommandSender sender) {
        try {
            return Optional.of(PayoutCategory.valueOf(token.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<String> suggestions(String prefix, CommandSender sender) {
        String normalized = prefix.toUpperCase(Locale.ROOT);
        return Arrays.stream(PayoutCategory.values())
                .map(Enum::name)
                .filter(name -> name.startsWith(normalized))
                .toList();
    }
}
