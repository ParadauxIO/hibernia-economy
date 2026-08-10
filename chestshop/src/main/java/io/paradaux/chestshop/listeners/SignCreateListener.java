package io.paradaux.chestshop.listeners;
import io.paradaux.chestshop.utils.SignText;

import io.paradaux.chestshop.services.ShopBlockService;
import com.google.inject.Inject;
import io.paradaux.chestshop.utils.BlockUtil;
import io.paradaux.chestshop.utils.StringUtil;
import io.paradaux.chestshop.ChestShop;
import io.paradaux.chestshop.model.ShopCreation;
import io.paradaux.chestshop.model.CreatedShop;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.chestshop.services.ItemService;
import io.paradaux.chestshop.services.ShopService;
import io.paradaux.chestshop.services.SignBreakService;
import io.paradaux.chestshop.services.SignService;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;

import static io.paradaux.chestshop.utils.Permissions.OTHER_NAME_DESTROY;

/**
 * @author Acrobot
 */
public class SignCreateListener implements Listener {

    private final AccountService accounts;
    private final ItemService items;
    private final ShopService shops;
    private final SignBreakService signBreak;
    private final SignService signService;
    private final ShopBlockService shopBlockService;

    @Inject
    public SignCreateListener(AccountService accounts, ItemService items, ShopService shops, SignBreakService signBreak,
                      SignService signService, ShopBlockService shopBlockService) {
        this.accounts = accounts;
        this.items = items;
        this.shops = shops;
        this.signBreak = signBreak;
        this.signService = signService;
        this.shopBlockService = shopBlockService;
    }

    @EventHandler(ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        Block signBlock = event.getBlock();

        if (!BlockUtil.isSign(signBlock)) {
            return;
        }

        Sign sign = (Sign) BlockUtil.getState(signBlock, false);

        if (signService.isValid(sign) && !accounts.canAccess(event.getPlayer(), sign)) {
            // There was already a shop here, but the player does not have permission to change it
            event.setCancelled(true);
            sign.update();
            ChestShop.logDebug("Shop sign creation at " + sign.getLocation() + " by " + event.getPlayer().getName() + " was cancelled as there already was a shop here and the player did not have permission to change it");
            return;
        }

        if (signService.isValid(event.getLines()) && !accounts.canUseName(event.getPlayer(), OTHER_NAME_DESTROY, SignService.getOwner(event.getLines()))) {
            event.setCancelled(true);
            sign.update();
            ChestShop.logDebug("Shop sign creation at " + sign.getLocation() + " by " + event.getPlayer().getName() + " was cancelled as they weren't able to create a shop for the account '" + SignService.getOwner(event.getLines()) + "'");
            return;
        }

        String[] lines = StringUtil.stripColourCodes(event.getLines());

        if (!signService.validateSign(lines)) {
            // Check if a valid shop already existed previously
            if (signService.isValid(sign)) {
                signBreak.sendShopDestroyed(sign, event.getPlayer());
            }
            return;
        }

        ShopCreation preEvent = shops.create(event.getPlayer(), sign, lines);

        if (preEvent.getOutcome().shouldBreakSign()) {
            event.setCancelled(true);
            signBlock.breakNaturally();
            ChestShop.logDebug("Shop sign creation at " + sign.getLocation() + " by " + event.getPlayer().getName() + " was cancelled (creation outcome: " + preEvent.getOutcome() + ") and the sign broken");
            return;
        }

        for (byte i = 0; i < preEvent.getSignLines().length && i < 4; ++i) {
            SignText.setLine(event, i, preEvent.getSignLine(i));
        }

        if (preEvent.isCancelled()) {
            ChestShop.logDebug("Shop sign creation at " + sign.getLocation() + " by " + event.getPlayer().getName() + " was cancelled (creation outcome: " + preEvent.getOutcome() + ") and sign lines were set to " + String.join(", ", preEvent.getSignLines()));
            return;
        }

        CreatedShop postEvent = new CreatedShop(preEvent.getPlayer(), preEvent.getSign(), shopBlockService.findConnectedContainer(preEvent.getSign()), preEvent.getSignLines(), preEvent.getOwnerAccount());
        shops.onCreated(postEvent);
    }
}
