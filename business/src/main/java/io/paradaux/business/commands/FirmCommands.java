package io.paradaux.business.commands;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import io.paradaux.hibernia.framework.commander.annotations.*;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.exceptions.InternalException;
import io.paradaux.hibernia.framework.i18n.Message;
// Every route here hits the DB and/or Treasury; @Async on each so the main
// server thread doesn't block on JDBC/IPC.

import io.paradaux.business.model.Firm;
import io.paradaux.business.model.FirmBalanceEntry;
import io.paradaux.business.model.FirmPlayer;
import io.paradaux.treasury.model.Page;
import io.paradaux.business.model.RolePermission;
import io.paradaux.business.utils.NameValidator;
import io.paradaux.business.chat.FirmChatService;
import io.paradaux.business.services.FirmDisbandConfirmationService;
import io.paradaux.business.services.FirmPlayerService;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.services.FirmStaffService;
import io.paradaux.business.services.FirmTransactionService;
import io.paradaux.business.commands.resolvers.FirmName;
import io.paradaux.business.commands.resolvers.OnlineFirmName;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

@Singleton
@Command({"db", "democracybusiness", "business", "firm", "company"})
public class FirmCommands implements CommandHandler {

    private static final int FIRM_PAGE_SIZE = 10;

    private final FirmPlayerService players;
    private final FirmStaffService staff; // Permission checks have to be done in this layer due to cyclic dependencies
    private final FirmService firms;
    private final FirmTransactionService transactions;
    private final FirmDisbandConfirmationService disbandConfirmations;
    private final FirmChatService firmChat;
    private final Message message;

    @Inject
    public FirmCommands(FirmPlayerService players, FirmStaffService staff, FirmService firms,
                        FirmTransactionService transactions, FirmDisbandConfirmationService disbandConfirmations,
                        FirmChatService firmChat, Message message) {
        this.players = players;
        this.staff = staff;
        this.firms = firms;
        this.transactions = transactions;
        this.disbandConfirmations = disbandConfirmations;
        this.firmChat = firmChat;
        this.message = message;
    }

    @Route("create <firm>")
    @Permission("business.create")
    @Async
    @Description("Creates a firm, with the associated default roles and a treasury account.")
    public void create(@Sender Player sender, @Arg("firm") String firmName) {
        // TODO: cost to open a business?
        Firm f = firms.createFirm(firmName, sender.getUniqueId());
        message.send(sender, "business.firm.create.success", "firm", firmName, "firmId", f.getFirmId());
        message.broadcast("business.firm.create.broadcast", "firm", firmName, "sender", sender.getName());
    }

    @Route("disband <firm>")
    @Permission("business.disband")
    @Async
    @Description("Disband a firm you own (asks for confirmation)")
    public void disband(@Sender Player sender, @Arg("firm") FirmName firmRef) {
        String firm = firmRef.value();
        // Archived-inclusive so an already-disbanded firm shows "already disbanded".
        Firm f = firms.getAnyFirmByNameOrId(firm);
        if (f == null) {
            message.send(sender, "business.firm.not-found", "firm", firm);
            return;
        }
        if (Boolean.TRUE.equals(f.getArchived())) {
            message.send(sender, "business.firm.disband.already", "firm", firm);
            return;
        }
        if (!firms.isProprietor(firm, sender.getUniqueId())) {
            message.send(sender, "business.general.no-permission");
            return;
        }

        // Disbanding is destructive and irreversible — warn, then require an explicit
        // confirm command before actually disbanding (PAR-93/PAR-86). Only record the
        // pending confirmation once the prompt has been built.
        String balance = transactions.getFormattedAggregateBalance(f.getFirmId());
        disbandConfirmations.request(sender.getUniqueId(), f.getFirmId());
        message.send(sender, "business.firm.disband.confirm-prompt",
                "firm", f.getDisplayName(), "balance", balance);
    }

    @Route("disband <firm> confirm")
    @Permission("business.disband")
    @Async
    @Description("Confirm disbanding a firm you own")
    public void disbandConfirm(@Sender Player sender, @Arg("firm") FirmName firmRef) {
        String firm = firmRef.value();
        Firm f = firms.getAnyFirmByNameOrId(firm);
        if (f == null) {
            message.send(sender, "business.firm.not-found", "firm", firm);
            return;
        }
        if (Boolean.TRUE.equals(f.getArchived())) {
            message.send(sender, "business.firm.disband.already", "firm", firm);
            return;
        }
        if (!firms.isProprietor(firm, sender.getUniqueId())) {
            message.send(sender, "business.general.no-permission");
            return;
        }
        if (!disbandConfirmations.consume(sender.getUniqueId(), f.getFirmId())) {
            message.send(sender, "business.firm.disband.confirm-expired", "firm", f.getDisplayName());
            return;
        }

        // Capture online employees BEFORE disband — afterwards they're no longer
        // current employees (firm is archived) so getOnlineEmployees() may return
        // an empty list depending on how it filters.
        var onlineEmployees = staff.getOnlineEmployees(firm);

        firms.disbandFirm(firm, sender.getUniqueId());
        message.send(sender, "business.firm.disband.success", "firm", f.getDisplayName());
        message.send(onlineEmployees, "business.firm.disband.employee", "firm", f.getDisplayName(), "sender", sender.getName());
        message.broadcast("business.firm.disband.broadcast", "firm", f.getDisplayName(), "sender", sender.getName());
    }

    @Route("list")
    @Permission("business.list")
    @Async
    @Description("List firms for the current user")
    public void list(@Sender Player sender) {
        list(sender, new FirmPlayer(sender.getUniqueId().toString(), sender.getName(), null, null, null));
    }

    @Route("list <user>")
    @Permission("business.list.other")
    @Async
    @Description("List firms for a user")
    public void list(@Sender Player sender, @Arg("user") FirmPlayer target) {
        List<Firm> list = firms.listOwnedOrMemberFirms(target.getUniqueId());
        if (list.isEmpty()) {
            message.send(sender, "business.firm.no-firms", "sender", target.getCurrentName());
            return;
        }

        message.send(sender, "business.firm.list.user.header", "sender", target.getCurrentName());
        sendFirms(sender, list);
    }

    @Route("list all")
    @Permission("business.list.all")
    @Async
    @Description("List all firms (page 1)")
    public void listAll(@Sender Player sender) {
        listAll(sender, 1);
    }

    @Route("list all <page>")
    @Permission("business.list.all")
    @Async
    @Description("List all firms")
    public void listAll(@Sender Player sender, @Arg("page") Integer page) {
        List<Firm> list = firms.listAllFirms(page, FIRM_PAGE_SIZE);

        if (list.isEmpty()) {
            message.send(sender, "business.firm.list.line.empty", "page", page);
            return;
        }

        message.send(sender, "business.firm.list.all.header", "page", page);
        sendFirms(sender, list);
    }

    @Route("baltop")
    @Permission("business.baltop")
    @Async
    @Description("Top firms by collective account balance")
    public void baltop(@Sender Player sender) {
        baltop(sender, 1);
    }

    @Route("baltop <page>")
    @Permission("business.baltop")
    @Async
    @Description("Page through the top firms by collective account balance")
    public void baltop(@Sender Player sender, @Arg("page") Integer page) {
        if (page == null || page < 1) page = 1;
        int pageSize = FIRM_PAGE_SIZE;

        Page<FirmBalanceEntry> result = transactions.getFirmBalanceTop(page, pageSize);

        if (result.items().isEmpty()) {
            message.send(sender, "business.firm.baltop.empty");
            return;
        }

        message.send(sender, "business.firm.baltop.header",
                "page", result.pageNumber(), "pages", result.totalPages());

        for (int i = 0; i < result.items().size(); i++) {
            FirmBalanceEntry entry = result.items().get(i);
            message.send(sender, "business.firm.baltop.entry",
                    "rank", result.offset() + i + 1,
                    "firm", entry.displayName(),
                    "balance", transactions.formatAmount(entry.balance()));
        }

        if (result.hasMore()) {
            message.send(sender, "business.firm.baltop.footer", "next", page + 1);
        }
    }

    @Route("info <firm>")
    @Permission("business.info")
    @Async
    @Description("Show firm info")
    public void info(@Sender Player sender, @Arg("firm") FirmName firmRef) {
        String firm = firmRef.value();
        // Archived-inclusive: info displays defunct firms (with a "(Defunct)" status).
        Firm f = firms.getAnyFirmByNameOrId(firm);

        if (f == null) {
            message.send(sender, "business.firm.not-found", "firm", firm);
            return;
        }

        Optional<FirmPlayer> proprietor = players.findByUuid(f.getProprietorUuid());
        if (proprietor.isEmpty()) {
            throw new InternalException("Failed to get the proprietor of this firm.");
        }

        String proprietorName = proprietor.get().getCurrentName();
        String balanceFmt = transactions.getFormattedAggregateBalance(f.getFirmId());
        String hq = f.getHqRegion() == null ? "N/A" : f.getHqRegion();
        String discord = f.getDiscordUrl() == null ? "N/A" : f.getDiscordUrl();
        String status = f.getArchived() ? "(Defunct)" : "";
        message.send(sender, "business.firm.info", "firm", f.getDisplayName(), "status", status, "owner", proprietorName, "balance", balanceFmt, "hq", hq, "discord", discord);
    }

    @Route("attribute set hq <firm> <plot>")
    @Permission("business.attribute.hq")
    @Async
    @Description("Set firm HQ to a valid AreaShop plot")
    public void setHq(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("plot") String plot) {
        String firm = firmRef.value();
        Firm f = firms.getFirmByNameOrId(firm);
        if (f == null) {
            message.send(sender, "business.firm.not-found", "firm", firm);
            return;
        }
        if (!staff.hasPermission(f.getFirmId(), sender.getUniqueId(), RolePermission.ADMIN)) {
            message.send(sender, "business.general.no-permission");
            return;
        }

        firms.updateFirmHq(firm, plot, sender.getUniqueId());
        message.send(sender, "business.firm.attribute.set.hq.success", "firm", firm, "hq", plot);
    }

    @Route("attribute set discord <firm> <url>")
    @Permission("business.attribute.discord")
    @Async
    @Description("Set firm Discord link")
    public void setDiscord(@Sender Player sender, @Arg("firm") FirmName firmRef, @GreedyArg(value = "url", sanitize = false) String url) {
        String firm = firmRef.value();
        Firm f = firms.getFirmByNameOrId(firm);
        if (f == null) {
            message.send(sender, "business.firm.not-found", "firm", firm);
            return;
        }
        if (!staff.hasPermission(f.getFirmId(), sender.getUniqueId(), RolePermission.ADMIN)) {
            message.send(sender, "business.general.no-permission");
            return;
        }

        // Strict invite charset (ADT discord-url-minimessage-injection): the old
        // \S+ permitted '<','>','&', letting a MiniMessage payload ride in the public
        // /firm info card. Require https + the real discord.gg code charset.
        if (!url.matches(NameValidator.DISCORD_INVITE_REGEX)) {
            message.send(sender, "business.firm.attribute.set.discord.invalid");
            return;
        }

        firms.updateFirmDiscord(firm, url, sender.getUniqueId());
        message.send(sender, "business.firm.attribute.set.discord.success", "firm", firm, "discord", url);

    }

    // ---- Staff/DOC administration (PAR-11) --------------------------------------
    // These bypass proprietor/firm-role checks and are gated on the operator's
    // server permission instead. The firm arg suggests firms with someone online
    // (OnlineFirmName) but still resolves any firm by name or id.

    @Route("admin disband <firm>")
    @Permission("business.admin.disband")
    @Async
    @Description("Force-disband a firm (staff/DOC override)")
    public void adminDisband(@Sender Player sender, @Arg("firm") OnlineFirmName firmRef) {
        String firm = firmRef.value();
        Firm f = firms.getAnyFirmByNameOrId(firm);
        if (f == null) {
            message.send(sender, "business.firm.not-found", "firm", firm);
            return;
        }
        if (Boolean.TRUE.equals(f.getArchived())) {
            message.send(sender, "business.firm.disband.already", "firm", firm);
            return;
        }

        var onlineEmployees = staff.getOnlineEmployees(firm);
        firms.adminDisbandFirm(firm);
        message.send(sender, "business.firm.disband.success", "firm", f.getDisplayName());
        message.send(onlineEmployees, "business.firm.disband.employee", "firm", f.getDisplayName(), "sender", sender.getName());
        message.broadcast("business.firm.disband.broadcast", "firm", f.getDisplayName(), "sender", sender.getName());
    }

    @Route("admin rename <firm> <newname>")
    @Permission("business.admin.rename")
    @Async
    @Description("Rename a firm (staff/DOC override)")
    public void adminRename(@Sender Player sender, @Arg("firm") OnlineFirmName firmRef, @Arg("newname") String newName) {
        String firm = firmRef.value();
        Firm f = firms.getFirmByNameOrId(firm);
        if (f == null) {
            message.send(sender, "business.firm.not-found", "firm", firm);
            return;
        }
        String old = f.getDisplayName();
        Firm renamed = firms.renameFirm(firm, newName);
        message.send(sender, "business.firm.admin.rename.success", "firm", old, "newname", renamed.getDisplayName());
    }

    @Route("admin set hq <firm> <plot>")
    @Permission("business.admin.attribute")
    @Async
    @Description("Set a firm's HQ (staff/DOC override)")
    public void adminSetHq(@Sender Player sender, @Arg("firm") OnlineFirmName firmRef, @Arg("plot") String plot) {
        String firm = firmRef.value();
        Firm f = firms.getFirmByNameOrId(firm);
        if (f == null) {
            message.send(sender, "business.firm.not-found", "firm", firm);
            return;
        }
        firms.adminSetHq(firm, plot);
        message.send(sender, "business.firm.attribute.set.hq.success", "firm", f.getDisplayName(), "hq", plot);
    }

    @Route("admin set discord <firm> <url>")
    @Permission("business.admin.attribute")
    @Async
    @Description("Set a firm's Discord URL (staff/DOC override)")
    public void adminSetDiscord(@Sender Player sender, @Arg("firm") OnlineFirmName firmRef, @GreedyArg(value = "url", sanitize = false) String url) {
        String firm = firmRef.value();
        Firm f = firms.getFirmByNameOrId(firm);
        if (f == null) {
            message.send(sender, "business.firm.not-found", "firm", firm);
            return;
        }
        // Strict invite charset (ADT discord-url-minimessage-injection): the old
        // \S+ permitted '<','>','&', letting a MiniMessage payload ride in the public
        // /firm info card. Require https + the real discord.gg code charset.
        if (!url.matches(NameValidator.DISCORD_INVITE_REGEX)) {
            message.send(sender, "business.firm.attribute.set.discord.invalid");
            return;
        }
        firms.adminSetDiscord(firm, url);
        message.send(sender, "business.firm.attribute.set.discord.success", "firm", f.getDisplayName(), "discord", url);
    }

    @Route("admin set proprietor <firm> <player>")
    @Permission("business.admin.proprietor")
    @Async
    @Description("Reassign a firm's proprietor (staff/DOC override)")
    public void adminSetProprietor(@Sender Player sender, @Arg("firm") OnlineFirmName firmRef, @Arg("player") OfflinePlayer target) {
        String firm = firmRef.value();
        Firm f = firms.getFirmByNameOrId(firm);
        if (f == null) {
            message.send(sender, "business.firm.not-found", "firm", firm);
            return;
        }
        // Don't reassign to a player Bukkit has never seen — an offline UUID is fabricated
        // for an unknown/typo'd name, which would orphan the firm to a ghost owner.
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            message.send(sender, "business.general.player-not-found");
            return;
        }
        firms.adminSetProprietor(firm, target.getUniqueId(), sender.getUniqueId());
        String targetName = target.getName() != null ? target.getName() : target.getUniqueId().toString();
        message.send(sender, "business.firm.admin.proprietor.success", "firm", f.getDisplayName(), "player", targetName);
    }

    @Route("admin chatspy")
    @Permission("business.admin.chatspy")
    @Async
    @Description("Toggle social spy on ALL firm chat (staff/DOC override)")
    public void adminChatSpyAll(@Sender Player sender) {
        if (!firmChat.available()) {
            message.send(sender, "business.chat.unavailable");
            return;
        }
        boolean on = firmChat.toggleGlobalSpy(sender.getUniqueId());
        message.send(sender, on ? "business.chat.spy.all-on" : "business.chat.spy.all-off");
    }

    @Route("admin chatspy <firm>")
    @Permission("business.admin.chatspy")
    @Async
    @Description("Toggle social spy on one firm's chat (staff/DOC override)")
    public void adminChatSpyFirm(@Sender Player sender, @Arg("firm") OnlineFirmName firmRef) {
        if (!firmChat.available()) {
            message.send(sender, "business.chat.unavailable");
            return;
        }
        String firm = firmRef.value();
        Firm f = firms.getFirmByNameOrId(firm);
        if (f == null) {
            message.send(sender, "business.firm.not-found", "firm", firm);
            return;
        }
        boolean on = firmChat.toggleFirmSpy(sender.getUniqueId(), f.getFirmId());
        message.send(sender, on ? "business.chat.spy.firm-on" : "business.chat.spy.firm-off", "firm", f.getDisplayName());
    }

    private void sendFirms(@Sender Player sender, List<Firm> list) {
        for (int i = 0; i < list.size(); i++) {
            Firm f = list.get(i);
            String name = f.getArchived() ? "<strikethrough>" + f.getDisplayName() + "</strikethrough>" : f.getDisplayName();
            String status = f.getArchived() ? "<red>Defunct</red>" : f.getFirmId().toString();
            // economy_players is populated on join (by Treasury) — proprietors who haven't
            // logged in to the new server (e.g. legacy firms imported via
            // TreasuryIngest) have no row yet. Fall back to the raw UUID rather
            // than crashing the entire listing on Optional.get().
            String owner = players.findByUuid(f.getProprietorUuid())
                    .map(FirmPlayer::getCurrentName)
                    .orElse(f.getProprietorUuid());
            message.send(sender, "business.firm.list.line", "ordinal", i + 1, "firm", name, "owner", owner, "status", status);
        }
    }
}
