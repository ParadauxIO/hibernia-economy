package io.paradaux.treasury.commands;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.services.AccountService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.util.Locale;

/**
 * Resolves an explicitly-typed account reference — {@code <type> <token>} where
 * type is one of {@code player | government | business | account} — to a concrete
 * account id. Shared by {@code /treasury admin transfer|balance|info} and
 * {@code /pay-account} so a player and a same-named business/government account are
 * never confused.
 *
 * <p>On any failure the resolver messages the sender and returns {@code null}.
 */
@Singleton
public final class AccountResolver {

    /** A resolved endpoint: the account id and a human-readable label. */
    public record Resolved(int accountId, String label) {}

    private final AccountService accountService;

    @Inject
    public AccountResolver(AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * @param side   a label for messages ("from"/"to"/"target")
     * @param create when true a missing PERSONAL account is created (money is
     *               moving to/from the player); when false a player with no
     *               Treasury account is reported rather than having one minted —
     *               a read must never create an account as a side effect.
     */
    public Resolved resolve(CommandSender sender, String side, String type, String token, boolean create) {
        switch (type.toLowerCase(Locale.ROOT)) {
            case "player" -> {
                OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(token);
                if (op == null || !(op.hasPlayedBefore() || op.isOnline())) {
                    sender.sendMessage("§cUnknown player for <" + side + ">: " + token);
                    return null;
                }
                int id;
                if (create) {
                    id = accountService.getOrCreatePersonalAccountId(op.getUniqueId());
                } else {
                    Integer existing = accountService.findPersonalAccountId(op.getUniqueId());
                    if (existing == null) {
                        sender.sendMessage("§cPlayer " + op.getName() + " has no Treasury account yet.");
                        return null;
                    }
                    id = existing;
                }
                return new Resolved(id, "player " + op.getName());
            }
            case "government", "gov" -> {
                Account a = accountService.getGovernmentAccountByName(token);
                if (a == null) {
                    sender.sendMessage("§cUnknown government account for <" + side + ">: " + token);
                    return null;
                }
                return new Resolved(a.getAccountId(), "government " + a.getDisplayName());
            }
            case "business", "firm" -> {
                Account a = accountService.resolveBusinessAccountByToken(token);
                if (a == null) {
                    sender.sendMessage("§cUnknown business account for <" + side + ">: " + token
                            + " (try the firm name, or use 'account <id>').");
                    return null;
                }
                return new Resolved(a.getAccountId(), "business " + a.getDisplayName());
            }
            case "account", "id" -> {
                int id;
                try {
                    id = Integer.parseInt(token);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cAccount id for <" + side + "> must be a number: " + token);
                    return null;
                }
                Account a = accountService.getAccountById(id);
                if (a == null) {
                    sender.sendMessage("§cNo account with id " + id + " for <" + side + ">.");
                    return null;
                }
                return new Resolved(a.getAccountId(), "account #" + id + " (" + a.getDisplayName() + ")");
            }
            default -> {
                sender.sendMessage("§c<" + side + "Type> must be one of: player, government, business, account "
                        + "(got '" + type + "').");
                return null;
            }
        }
    }
}
