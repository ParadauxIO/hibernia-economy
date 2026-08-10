package io.paradaux.chestshop.commands;

import io.paradaux.chestshop.services.ShopBlockService;
import com.google.inject.Inject;
import io.paradaux.chestshop.services.InfoService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * @author Phoenix616
 */
@Command({"shopinfo", "sinfo", "shop"})
@io.paradaux.hibernia.framework.commander.annotations.Permission("ChestShop.shopinfo")
public class ShopInfoCommand implements CommandHandler {

    private final InfoService info;
    private final Message message;
    private final SignService signService;
    private final ShopBlockService shopBlockService;

    @Inject
    public ShopInfoCommand(InfoService info, Message message, SignService signService, ShopBlockService shopBlockService) {
        this.info = info;
        this.message = message;
        this.signService = signService;
        this.shopBlockService = shopBlockService;
    }

    @Route("")
    public void shopInfo(@Sender CommandSender sender) {
        if (sender instanceof Player) {
            Block target = ((Player) sender).getTargetBlockExact(5);
            if (target != null) {
                Sign sign = null;
                if (signService.isValid(target)) {
                    sign = (Sign) target.getState();
                } else if (shopBlockService.couldBeShopContainer(target)) {
                    sign = shopBlockService.getConnectedSign(target);
                }

                if (sign != null) {
                    info.showShopInfo((Player) sender, sign);
                } else {
                    message.send(sender, "chestshop.NO_SHOP_FOUND");
                }
            }
        } else {
            message.send(sender, "chestshop.PLAYER_ONLY");
        }
    }
}
