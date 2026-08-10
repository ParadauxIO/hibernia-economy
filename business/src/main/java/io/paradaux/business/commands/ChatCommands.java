package io.paradaux.business.commands;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.hibernia.framework.commander.annotations.*;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.business.chat.FirmChatService;
import io.paradaux.business.model.Firm;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.commands.resolvers.FirmName;
import org.bukkit.entity.Player;

import java.util.Optional;

/**
 * {@code /firm chat} — enter the employee-only firm chat channel (PAR-20),
 * akin to Factions' {@code /f chat}. {@code /firm chat} joins the player's sole
 * firm; {@code /firm chat <firm>} picks one when they're in several;
 * {@code /firm chat off} leaves. Routing is handled by the CarbonChat channel
 * once selected; this command just sets the active firm + selects the channel.
 */
@Singleton
@Command({"db", "democracybusiness", "business", "firm", "company"})
public class ChatCommands implements CommandHandler {

    private final FirmChatService firmChat;
    private final FirmService firms;
    private final Message message;

    @Inject
    public ChatCommands(FirmChatService firmChat, FirmService firms, Message message) {
        this.firmChat = firmChat;
        this.firms = firms;
        this.message = message;
    }

    @Route("chat")
    @Permission("business.chat")
    @Async
    @Description("Enter your firm's chat channel")
    public void chat(@Sender Player sender) {
        enter(sender, null);
    }

    @Route("chat off")
    @Permission("business.chat")
    @Async
    @Description("Leave firm chat")
    public void chatOff(@Sender Player sender) {
        firmChat.leaveChat(sender);
        message.send(sender, "business.chat.left");
    }

    @Route("chat <firm>")
    @Permission("business.chat")
    @Async
    @Description("Enter a specific firm's chat channel")
    public void chatFirm(@Sender Player sender, @Arg("firm") FirmName firmRef) {
        Firm firm = firms.getFirmByNameOrId(firmRef.value());
        if (firm == null) {
            message.send(sender, "business.general.firm-not-found");
            return;
        }
        enter(sender, firm.getFirmId());
    }

    private void enter(Player sender, Integer firmId) {
        if (!firmChat.available()) {
            message.send(sender, "business.chat.unavailable");
            return;
        }
        Optional<Firm> entered = firmChat.enterChat(sender, firmId);
        if (entered.isEmpty()) {
            if (firmId == null && firmChat.inMultipleFirms(sender.getUniqueId())) {
                message.send(sender, "business.chat.specify-firm");
            } else {
                message.send(sender, "business.chat.not-member");
            }
            return;
        }
        message.send(sender, "business.chat.entered", "firm", entered.get().getDisplayName());
    }
}
