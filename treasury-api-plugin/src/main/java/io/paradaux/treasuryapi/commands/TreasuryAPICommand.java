package io.paradaux.treasuryapi.commands;

import com.google.inject.Inject;
import com.google.inject.Provider;
import io.paradaux.hibernia.framework.commander.CommandManager;
import io.paradaux.hibernia.framework.commander.HelpGenerator;
import io.paradaux.hibernia.framework.commander.annotations.*;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasuryapi.TreasuryAPI;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

@Command({"treasuryapi"})
@Permission("treasuryapi.command")
public class TreasuryAPICommand implements CommandHandler {

    @Inject private TreasuryAPI plugin;
    @Inject private Message message;
    // Provider breaks the construction cycle: CommandManager injects the
    // Set<CommandHandler> (which includes this class), and HelpGenerator takes a
    // CommandManager — so @Inject-ing HelpGenerator directly is circular and Guice
    // can't proxy the concrete CommandManager. Mirrors the Treasury/Business pattern.
    @Inject private Provider<CommandManager> commandManager;
    @Inject private PersonalKeyHandler personalHandler;
    @Inject private BusinessKeyHandler businessHandler;
    @Inject private UiAccessHandler uiHandler;

    // /treasuryapi — plugin info
    @Route("")
    @Description("Show TreasuryAPI plugin information")
    public void info(@Sender CommandSender sender) {
        message.send(sender, "treasuryapi.info",
                "version", plugin.getDescription().getVersion());
    }

    // /treasuryapi help [page] — paginated, permission-filtered help built from the
    // registered routes (each carries its @Description), so it stays in sync with the
    // real commands instead of being hand-maintained.
    @Route("help [page]")
    @Description("Show command help")
    public void help(@Sender CommandSender sender,
                     @OptionalArg(value = "page", defaultValue = "1") int page) {
        sender.sendMessage(new HelpGenerator(commandManager.get()).render(sender, "treasuryapi", page));
    }

    // ---- Personal ----

    @Route("personal issue")
    @Permission("treasuryapi.personal.issue")
    @Async
    @Description("Issue an API key for your personal Treasury account")
    public void personalIssue(@Sender Player sender) {
        personalHandler.doIssue(sender);
    }

    @Route("personal list")
    @Permission("treasuryapi.personal.list")
    @Async
    @Description("List personal API keys you have issued")
    public void personalList(@Sender Player sender) {
        personalHandler.doList(sender);
    }

    @Route("personal reissue <keyId>")
    @Permission("treasuryapi.personal.reissue")
    @Async
    @Description("Reissue a personal API key (rotates the token, valid for another 180 days)")
    public void personalReissue(@Sender Player sender,
                                @Arg("keyId") int keyId) {
        personalHandler.doReissue(sender, keyId);
    }

    @Route("personal revoke <keyId>")
    @Permission("treasuryapi.personal.revoke")
    @Async
    @Description("Permanently revoke a personal API key")
    public void personalRevoke(@Sender Player sender,
                               @Arg("keyId") int keyId) {
        personalHandler.doRevoke(sender, keyId);
    }

    // ---- Business ----

    @Route("business issue <firmName>")
    @Permission("treasuryapi.business.issue")
    @Async
    @Description("Issue a business-scoped API key for an entire firm (proprietors only)")
    public void businessIssue(@Sender Player sender,
                              @Arg("firmName") String firmName) {
        businessHandler.doIssue(sender, firmName);
    }

    @Route("business list")
    @Permission("treasuryapi.business.list")
    @Async
    @Description("List business API keys you have issued")
    public void businessList(@Sender Player sender) {
        businessHandler.doList(sender);
    }

    @Route("business list access")
    @Permission("treasuryapi.business.list")
    @Async
    @Description("List business API keys for firms you are employed at")
    public void businessListAccess(@Sender Player sender) {
        businessHandler.doListAccess(sender);
    }

    @Route("business reissue <keyId>")
    @Permission("treasuryapi.business.reissue")
    @Async
    @Description("Reissue a business API key (rotates the token, valid for another 180 days)")
    public void businessReissue(@Sender Player sender,
                                @Arg("keyId") int keyId) {
        businessHandler.doReissue(sender, keyId);
    }

    @Route("business revoke <keyId>")
    @Permission("treasuryapi.business.revoke")
    @Async
    @Description("Permanently revoke a business API key")
    public void businessRevoke(@Sender Player sender,
                               @Arg("keyId") int keyId) {
        businessHandler.doRevoke(sender, keyId);
    }

    // ---- Explorer UI access ----

    @Route("ui user add <role> <player>")
    @Permission("treasuryapi.ui.user")
    @Async
    @Description("Grant an explorer role (admin|government) to a player")
    public void uiUserAdd(@Sender CommandSender sender,
                          @Arg("role") String role,
                          @Arg("player") String player) {
        uiHandler.doUserAdd(sender, role, player);
    }

    @Route("ui user remove <role> <player>")
    @Permission("treasuryapi.ui.user")
    @Async
    @Description("Revoke an explorer role from a player")
    public void uiUserRemove(@Sender CommandSender sender,
                             @Arg("role") String role,
                             @Arg("player") String player) {
        uiHandler.doUserRemove(sender, role, player);
    }

    @Route("ui user list <player>")
    @Permission("treasuryapi.ui.user")
    @Async
    @Description("Show a player's explorer roles")
    public void uiUserList(@Sender CommandSender sender,
                           @Arg("player") String player) {
        uiHandler.doUserList(sender, player);
    }

    @Route("ui link <code>")
    @Permission("treasuryapi.ui.link")
    @Async
    @Description("Link your Minecraft account to your explorer login using a code from the web UI")
    public void uiLink(@Sender Player sender,
                       @Arg("code") String code) {
        uiHandler.doLink(sender, code);
    }

    @Route("ui sync")
    @Permission("treasuryapi.ui.sync")
    @Async
    @Description("Sync your explorer roles from your current in-game ranks")
    public void uiSync(@Sender Player sender) {
        uiHandler.doSelfSync(sender);
    }
}
