package io.paradaux.treasury.commands;

import com.google.inject.Inject;
import io.paradaux.hibernia.framework.commander.annotations.*;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasury.model.config.EconomyConfiguration;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.utils.Money;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.UUID;


@Command({"eco"})
@Permission("treasury.eco")
public class EcoCommand implements CommandHandler {

    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final Message message;
    private final EconomyConfiguration economyConfig;

    @Inject
    public EcoCommand(AccountService accountService,
                      LedgerService ledgerService,
                      Message message,
                      EconomyConfiguration economyConfig) {
        this.accountService = accountService;
        this.ledgerService = ledgerService;
        this.message = message;
        this.economyConfig = economyConfig;
    }

    @Route("")
    @Description("Show /eco help")
    public void root(@Sender CommandSender sender) {
        message.send(sender, "treasury.help.eco");
    }

    @Route("help")
    @Description("Show /eco help")
    public void help(@Sender CommandSender sender) {
        message.send(sender, "treasury.help.eco");
    }

    @Route("give <target> <amount>")
    @Permission("treasury.eco.give")
    @Async
    @Description("Give money to a player")
    public void give(@Sender CommandSender sender,
                     @Arg("target") OfflinePlayer target,
                     @Arg("amount") BigDecimal amount) {
        if (target == null || (!target.hasPlayedBefore() && !target.isOnline())) {
            message.send(sender, "treasury.general.unknown-player");
            return;
        }
        BigDecimal normalized = Money.normalize(amount);
        if (normalized.signum() <= 0) {
            message.send(sender, "treasury.general.invalid-amount");
            return;
        }

        UUID adminUuid = CommandSenders.actorOf(sender);
        try {
            ledgerService.adminGive(target.getUniqueId(), normalized,
                    "Admin give by " + sender.getName(), adminUuid);
        } catch (IllegalStateException | IllegalArgumentException e) {
            // Mirror /eco take's defensive handling: surface a clean message instead
            // of leaking a raw ledger exception if the give can't be applied (ADT-55).
            message.send(sender, "treasury.general.invalid-amount");
            return;
        }

        String formattedAmount = accountService.formatAmount(normalized);
        message.send(sender, "treasury.eco.give.success",
                "amount", formattedAmount, "target", target.getName());

        Player onlineTarget = target.getPlayer();
        if (onlineTarget != null) {
            message.send(onlineTarget, "treasury.eco.give.notify", "amount", formattedAmount);
        }
    }

    @Route("take <target> <amount>")
    @Permission("treasury.eco.take")
    @Async
    @Description("Take money from a player")
    public void take(@Sender CommandSender sender,
                     @Arg("target") OfflinePlayer target,
                     @Arg("amount") BigDecimal amount) {
        if (target == null || (!target.hasPlayedBefore() && !target.isOnline())) {
            message.send(sender, "treasury.general.unknown-player");
            return;
        }
        BigDecimal normalized = Money.normalize(amount);
        if (normalized.signum() <= 0) {
            message.send(sender, "treasury.general.invalid-amount");
            return;
        }

        int targetAccountId = accountService.getOrCreatePersonalAccountId(target.getUniqueId());
        BigDecimal targetBalance = accountService.getBalanceReadOnly(targetAccountId);
        if (targetBalance.compareTo(normalized) < 0) {
            message.send(sender, "treasury.eco.take.insufficient",
                    "target", target.getName(),
                    "balance", accountService.formatAmount(targetBalance));
            return;
        }

        UUID adminUuid = CommandSenders.actorOf(sender);
        try {
            ledgerService.adminTake(target.getUniqueId(), normalized,
                    "Admin take by " + sender.getName(), adminUuid);
        } catch (IllegalStateException e) {
            BigDecimal currentBalance = accountService.getBalanceReadOnly(targetAccountId);
            message.send(sender, "treasury.eco.take.insufficient",
                    "target", target.getName(),
                    "balance", accountService.formatAmount(currentBalance));
            return;
        }

        String formattedAmount = accountService.formatAmount(normalized);
        message.send(sender, "treasury.eco.take.success",
                "amount", formattedAmount, "target", target.getName());

        Player onlineTarget = target.getPlayer();
        if (onlineTarget != null) {
            message.send(onlineTarget, "treasury.eco.take.notify", "amount", formattedAmount);
        }
    }

    @Route("set <target> <amount>")
    @Permission("treasury.eco.set")
    @Async
    @Description("Set a player's balance")
    public void set(@Sender CommandSender sender,
                    @Arg("target") OfflinePlayer target,
                    @Arg("amount") BigDecimal amount) {
        if (target == null || (!target.hasPlayedBefore() && !target.isOnline())) {
            message.send(sender, "treasury.general.unknown-player");
            return;
        }
        // Reject sub-cent precision BEFORE normalize, so 100.001 doesn't
        // silently round to 100.00.
        try {
            Money.requireValidScale(amount);
        } catch (IllegalArgumentException e) {
            message.send(sender, "treasury.general.invalid-amount");
            return;
        }
        BigDecimal normalized = Money.normalize(amount);
        // Asymmetric with give/take on purpose: setting a balance to exactly
        // 0 is a valid admin action (e.g., zeroing out a banned player's
        // funds), while giving/taking 0 is a no-op. signum() < 0 only.
        if (normalized.signum() < 0) {
            message.send(sender, "treasury.general.invalid-amount");
            return;
        }

        UUID adminUuid = CommandSenders.actorOf(sender);
        ledgerService.adminSet(target.getUniqueId(), normalized,
                "Admin set balance by " + sender.getName(), adminUuid);

        String formattedAmount = accountService.formatAmount(normalized);
        message.send(sender, "treasury.eco.set.success",
                "target", target.getName(), "amount", formattedAmount);

        Player onlineTarget = target.getPlayer();
        if (onlineTarget != null) {
            message.send(onlineTarget, "treasury.eco.set.notify", "amount", formattedAmount);
        }
    }

    @Route("reset <target>")
    @Permission("treasury.eco.reset")
    @Async
    @Description("Reset a player's balance to the starting amount")
    public void reset(@Sender CommandSender sender,
                      @Arg("target") OfflinePlayer target) {
        if (target == null || (!target.hasPlayedBefore() && !target.isOnline())) {
            message.send(sender, "treasury.general.unknown-player");
            return;
        }

        UUID adminUuid = CommandSenders.actorOf(sender);
        ledgerService.adminReset(target.getUniqueId(), adminUuid);

        BigDecimal startingBalance = Money.normalize(economyConfig.getStartingBalance());
        String formattedAmount = accountService.formatAmount(startingBalance);
        message.send(sender, "treasury.eco.reset.success",
                "target", target.getName(), "amount", formattedAmount);

        Player onlineTarget = target.getPlayer();
        if (onlineTarget != null) {
            message.send(onlineTarget, "treasury.eco.reset.notify", "amount", formattedAmount);
        }
    }
}
