package io.paradaux.business.commands;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.hibernia.framework.commander.annotations.*;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.exceptions.InternalException;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.business.model.Firm;
import io.paradaux.business.services.FirmNotificationService;
import io.paradaux.business.services.FirmRequestService;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.services.FirmStaffService;
import io.paradaux.business.commands.resolvers.FirmName;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

@Singleton
@Command({"db", "democracybusiness", "business", "firm", "company"})
public class RequestCommands implements CommandHandler {

    private final FirmRequestService requests;
    private final FirmStaffService staff;
    private final FirmService firms;
    private final FirmNotificationService notifications;
    private final Message message;

    @Inject
    public RequestCommands(FirmRequestService requests, FirmStaffService staff, FirmService firms,
                           FirmNotificationService notifications, Message message) {
        this.requests = requests;
        this.staff = staff;
        this.firms = firms;
        this.notifications = notifications;
        this.message = message;
    }

    @Route("offer <firm> <user>")
    @Route("hire <firm> <user>")
    @Permission("business.staff.offer")
    @Async
    @Description("Offer employment (5m to accept)")
    public void offer(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("user") OfflinePlayer target) {
        String firm = firmRef.value();
        requests.offerEmployment(firm, target.getUniqueId(), sender.getUniqueId());
        message.send(sender, "business.staff.offer.sender", "target", displayName(target), "firm", firm);

        if (target.isOnline() && target.getPlayer() != null) {
            message.send(target.getPlayer(), "business.staff.offer.target", "firm", firm, "sender", sender.getName());
        }

        message.send(staff.getOnlineEmployees(firm), "business.staff.other.staff-broadcast", "target", displayName(target), "sender", sender.getName(), "firm", firm);
    }

    @Route("offer rescind <firm> <user>")
    @Permission("business.staff.offer.rescind")
    @Async
    @Description("Rescind employment offer")
    public void rescind(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("user") OfflinePlayer target) {
        String firm = firmRef.value();
        requests.rescindEmploymentOffer(firm, target.getUniqueId(), sender.getUniqueId());

        message.send(sender, "business.staff.offer.rescinded.sender", "firm", firm, "target", displayName(target));

        if (target.isOnline() && target.getPlayer() != null) {
            message.send(target.getPlayer(), "business.staff.offer.rescinded.target", "firm", firm, "sender", sender.getName());
        }

        message.send(staff.getOnlineEmployees(firm), "business.staff.other.rescinded.staff-broadcast", "sender", sender.getName(), "target", displayName(target), "firm", firm);
    }

    // ADT (offer-target-name-npe): OfflinePlayer.getName() is null for a UUID Bukkit
    // has never cached (a typo'd/never-joined target), which would NPE inside the
    // @Async handler after the invite was already persisted. Fall back to the UUID.
    private static String displayName(OfflinePlayer player) {
        return player.getName() != null ? player.getName() : player.getUniqueId().toString();
    }

    @Route("offer accept <firm>")
    @Permission("business.staff.offer.accept")
    @Async
    @Description("Accept a pending job offer")
    public void accept(@Sender Player sender, @Arg("firm") FirmName firmRef) {
        String firm = firmRef.value();
        requests.acceptEmploymentOffer(firm, sender.getUniqueId(), sender.getUniqueId());
        message.send(sender, "business.staff.offer.accept", "firm", firm);
        // Notify the firm of the new hire, excluding the joiner (they got the
        // direct confirmation above) so they aren't told they joined themselves.
        Firm f = firms.getFirmByNameOrId(firm);
        if (f != null) {
            notifications.notifyFirmExcept(f.getFirmId(), sender.getUniqueId(), "business.staff.offer.accept.staff-broadcast",
                    "sender", sender.getName(), "firm", f.getDisplayName());
        }
    }

    @Route("offer reject <firm>")
    @Permission("business.staff.offer.reject")
    @Async
    @Description("Reject a pending job offer")
    public void reject(@Sender Player sender, @Arg("firm") FirmName firmRef) {
        String firm = firmRef.value();
        requests.rejectEmploymentOffer(firm, sender.getUniqueId(), sender.getUniqueId());
        message.send(sender, "business.staff.offer.reject", "firm", firm);
        message.send(staff.getOnlineEmployees(firm), "business.staff.offer.reject.staff-broadcast", "sender", sender.getName(), "firm", firm);
    }

// --------------------- TRANSFER PROPRIETORSHIP ---------------------

    @Route("transfer begin <firm> <user>")
    @Permission("business.transfer.begin")
    @Async
    @Description("Begin proprietorship transfer (click-to-confirm)")
    public void beginTransfer(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("user") OfflinePlayer target) {
        String firm = firmRef.value();
        String code = requests.beginTransferProprietorship(firm, target.getUniqueId(), sender.getUniqueId());
        message.send(sender, "business.firm.transfer.begin", "firm", firm, "target", displayName(target), "code", code);
    }

    @Route("transfer confirm <firm> <user> <code>")
    @Permission("business.transfer.confirm")
    @Async
    @Description("Begin proprietorship transfer (click-to-confirm)")
    public void confirmTransfer(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("user") OfflinePlayer target, @Arg("code") String code) {
        String firm = firmRef.value();
        if (requests.confirmTransferProprietorship(firm, target.getUniqueId(), code, sender.getUniqueId())) {
            message.send(sender, "business.firm.transfer.confirmed", "target", displayName(target));

            if (target.isOnline() && target.getPlayer() != null) {
                message.send(target.getPlayer(), "business.firm.transfer.offer", "firm", firm, "sender", sender.getName(), "target", displayName(target));
                message.send(staff.getOnlineEmployees(firm), "business.firm.transfer.confirmed.staff-broadcast", "sender", sender.getName(), "firm", firm, "target", displayName(target));
            }
        } else {
            message.send(sender, "business.firm.transfer.invalid-code");
        }
    }

    @Route("transfer cancel <firm> <user>")
    @Permission("business.transfer.cancel")
    @Async
    public void transferCancel(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("user") OfflinePlayer target) {
        String firm = firmRef.value();
        requests.cancelTransferProprietorship(firm, target.getUniqueId(), sender.getUniqueId());

        message.send(sender, "business.firm.transfer.cancelled.sender", "firm", firm, "target", displayName(target));
        message.send(staff.getOnlineEmployees(firm), "business.firm.transfer.cancelled.staff-broadcast", "target", displayName(target), "firm", firm);
        if (target.isOnline() && target.getPlayer() != null) {
            message.send(target.getPlayer(), "business.firm.transfer.cancelled.target", "firm", firm, "sender", sender.getName());
        }
    }

    @Route("transfer complete <firm> <user>")
    @Permission("business.transfer.complete")
    @Async
    public void transferComplete(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("user") OfflinePlayer target) {
        String firm = firmRef.value();
        UUID previousProprietor = requests.completeTransferProprietorship(firm, sender.getUniqueId());

        message.send(sender, "business.firm.transfer.completed.sender", "firm", firm);
        message.send(previousProprietor, "business.firm.transfer.completed.old-owner", "firm", firm);
        message.send(staff.getOnlineEmployees(firm), "business.firm.transfer.completed.staff-broadcast", "target", displayName(target), "firm", firm);
    }

    @Route("transfer reject <firm> <user>")
    @Permission("business.transfer.reject")
    @Async
    public void transferReject(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("user") OfflinePlayer target) {
        String firm = firmRef.value();
        UUID previousProprietor = requests.rejectTransferProprietorship(firm, sender.getUniqueId(), sender.getUniqueId());

        message.send(sender, "business.firm.transfer.rejected", "firm", firm);
        message.send(staff.getOnlineEmployees(firm), "business.firm.transfer.rejected.staff-broadcast","target", displayName(target), "firm", firm);
        message.send(previousProprietor, "business.firm.transfer.rejected.old-owner", "target", displayName(target), "firm", firm);
    }
}
