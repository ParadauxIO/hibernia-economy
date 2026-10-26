package io.paradaux.business.commands;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.hibernia.framework.commander.annotations.*;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import lombok.extern.slf4j.Slf4j;
// Every staff command hits the DB and is annotated @Async to avoid main-thread blocking.
import io.paradaux.business.model.Firm;
import io.paradaux.business.model.FirmEmployee;
import io.paradaux.business.model.FirmPlayer;
import io.paradaux.business.services.FirmNotificationService;
import io.paradaux.business.services.FirmPlayerService;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.services.FirmStaffService;
import io.paradaux.business.utils.DateUtils;
import io.paradaux.business.commands.resolvers.FirmName;
import org.bukkit.entity.Player;

import java.util.List;

@Slf4j
@Singleton
@Command({"db", "democracybusiness", "business", "firm", "company"})
public class StaffCommands implements CommandHandler {

    private final FirmPlayerService players;
    private final FirmStaffService staff;
    private final FirmService firms;
    private final FirmNotificationService notifications;
    private final Message message;

    @Inject
    public StaffCommands(FirmPlayerService players, FirmStaffService staff, FirmService firms,
                         FirmNotificationService notifications, Message message) {
        this.players = players;
        this.staff = staff;
        this.firms = firms;
        this.notifications = notifications;
        this.message = message;
    }

    @Route("fire <firm> <user>")
    @Permission("business.staff.fire")
    @Async
    @Description("Fire a user from a firm you control")
    public void fire(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("user") FirmPlayer target) {
        String firm = firmRef.value();
        staff.fireEmployee(firm, target.getUniqueId(), sender.getUniqueId());
        message.send(sender, "business.staff.fire.sender", "target", target.getCurrentName(), "firm", firm);

        if (players.isOnline(target)) {
            message.send(target, "business.staff.fire.target", "firm", firm, "sender", sender.getName());
        }
        // Tell the rest of the firm; the fired player already got the direct message above.
        Firm f = firms.getFirmByNameOrId(firm);
        if (f != null) {
            notifications.notifyFirmExcept(f.getFirmId(), target.getUniqueId(), "business.staff.fire.staff-broadcast",
                    "target", target.getCurrentName(), "firm", f.getDisplayName());
        }
    }

    @Route("resign <firm>")
    @Permission("business.staff.resign")
    @Async
    @Description("Resign from a firm")
    public void resign(@Sender Player sender, @Arg("firm") FirmName firmRef) {
        String firm = firmRef.value();
        staff.resignFromFirm(firm, sender.getUniqueId());
        message.send(sender, "business.staff.resign.sender", "firm", firm);
        // The resigner has already left the firm, so they're no longer a recipient.
        Firm f = firms.getFirmByNameOrId(firm);
        if (f != null) {
            notifications.notifyFirm(f.getFirmId(), "business.staff.resign.staff-broadcast",
                    "target", sender.getName(), "firm", f.getDisplayName());
        }
    }

    @Route("staff promote <firm> <user>")
    @Permission("business.staff.promote")
    @Async
    @Description("Promote user to next role")
    public void promote(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("user") FirmPlayer target) {
        String firm = firmRef.value();
        log.debug("promote: firm={} target={}", firm, target.getCurrentName());
        String nextRole = staff.promoteEmployee(firm, target.getUniqueId(), sender.getUniqueId());
        message.send(sender, "business.staff.promote.sender", "target", target.getCurrentName(), "firm", firm, "role", nextRole);

        if (players.isOnline(target)) {
            message.send(target, "business.staff.promote.target", "role", nextRole, "sender", sender.getName(), "firm", firm);
        }

        // Firm-wide visibility; the promoted player already got the direct message above.
        Firm f = firms.getFirmByNameOrId(firm);
        if (f != null) {
            notifications.notifyFirmExcept(f.getFirmId(), target.getUniqueId(), "business.staff.promote.staff-broadcast",
                    "target", target.getCurrentName(), "role", nextRole, "firm", f.getDisplayName());
        }
    }

    @Route("staff demote <firm> <user>")
    @Permission("business.staff.demote") // as per spec; feels like a typo but preserving
    @Async
    @Description("Demote user to previous role")
    public void demote(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("user") FirmPlayer target) {
        String firm = firmRef.value();
        log.debug("demote: firm={} target={}", firm, target.getCurrentName());
        String nextRole = staff.demoteEmployee(firm, target.getUniqueId(), sender.getUniqueId());
        message.send(sender, "business.staff.demote.sender", "target", target.getCurrentName(), "firm", firm, "role", nextRole);

        if (players.isOnline(target)) {
            message.send(target, "business.staff.demote.target", "role", nextRole, "sender", sender.getName(), "firm", firm);
        }
        // Firm-wide visibility; the demoted player already got the direct message above.
        Firm f = firms.getFirmByNameOrId(firm);
        if (f != null) {
            notifications.notifyFirmExcept(f.getFirmId(), target.getUniqueId(), "business.staff.demote.staff-broadcast",
                    "target", target.getCurrentName(), "role", nextRole, "firm", f.getDisplayName());
        }
    }

    @Route("staff list <firm>")
    @Permission("business.staff.list") // as per spec
    @Async
    @Description("List staff within a firm you own or manage")
    public void listStaff(@Sender Player sender, @Arg("firm") FirmName firmRef) {
        String firm = firmRef.value();
        List<FirmEmployee> employees = staff.getCurrentEmployees(firm);

        message.send(sender, "business.staff.list.header", "firm", firm);
        for (int i = 0; i < employees.size(); i++) {
            FirmEmployee e = employees.get(i);
            // economy_players is populated on join (by Treasury) — staff imported via
            // TreasuryIngest whose UUIDs haven't logged in yet have no row.
            // Fall back to the raw UUID rather than crashing the listing.
            String employeeName = players.findByUuid(e.getPlayerUuid())
                    .map(FirmPlayer::getCurrentName)
                    .orElse(e.getPlayerUuid());
            String date = DateUtils.localDateToAmericanDateString(e.getJoinedAt());
            message.send(sender, "business.staff.list.line", "ordinal", i + 1, "role", e.getRoleName(), "date", date, "name", employeeName);
        }
    }
}
