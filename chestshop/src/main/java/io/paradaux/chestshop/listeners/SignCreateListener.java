package io.paradaux.chestshop.listeners;

import static io.paradaux.chestshop.utils.Permissions.OTHER_NAME_DESTROY;

import com.google.inject.Inject;
import io.paradaux.chestshop.ChestShop;
import io.paradaux.chestshop.model.CreatedShop;
import io.paradaux.chestshop.model.ShopCreation;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.chestshop.services.ShopBlockService;
import io.paradaux.chestshop.services.ShopService;
import io.paradaux.chestshop.services.SignBreakService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.chestshop.utils.BlockUtil;
import io.paradaux.chestshop.utils.SignText;
import io.paradaux.chestshop.utils.StringUtil;
import java.util.Arrays;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;

/**
 * @author Acrobot
 */
public class SignCreateListener implements Listener {

    private final AccountService accounts;
    private final ShopService shops;
    private final SignBreakService signBreak;
    private final SignService signService;
    private final ShopBlockService shopBlockService;

    @Inject
    public SignCreateListener(
            AccountService accounts,
            ShopService shops,
            SignBreakService signBreak,
            SignService signService,
            ShopBlockService shopBlockService) {
        this.accounts = accounts;
        this.shops = shops;
        this.signBreak = signBreak;
        this.signService = signService;
        this.shopBlockService = shopBlockService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        if (event.isCancelled() || event.getSide() != Side.FRONT) return;
        Block signBlock = event.getBlock();

        if (!BlockUtil.isSign(signBlock)) {
            return;
        }

        Sign sign = (Sign) BlockUtil.getState(signBlock, false);

        boolean shopExisted = signService.isValid(sign);
        if (shopExisted && !accounts.canAccess(event.getPlayer(), sign)) {
            // There was already a shop here, but the player does not have permission to change it
            event.setCancelled(true);
            sign.update();
            ChestShop.logDebug(
                    "Shop sign creation at " + sign.getLocation() + " by "
                            + event.getPlayer().getName()
                            + " was cancelled as there already was a shop here and the player did not have permission to change it");
            return;
        }

        if (signService.isValid(event.getLines())
                && !accounts.canUseName(
                        event.getPlayer(), OTHER_NAME_DESTROY, SignService.getOwner(event.getLines()))) {
            event.setCancelled(true);
            sign.update();
            ChestShop.logDebug("Shop sign creation at " + sign.getLocation() + " by "
                    + event.getPlayer().getName()
                    + " was cancelled as they weren't able to create a shop for the account '"
                    + SignService.getOwner(event.getLines()) + "'");
            return;
        }

        if (shopExisted && Arrays.equals(event.getLines(), sign.getLines())) return;

        String[] lines = StringUtil.stripColourCodes(event.getLines());

        if (!signService.validateSign(lines)) {
            // Check if a valid shop already existed previously
            if (shopExisted) {
                signBreak.sendShopDestroyed(sign, event.getPlayer());
            }
            return;
        }

        ShopCreation preEvent = shops.create(event.getPlayer(), sign, lines);

        // A failed replacement must leave the existing shop and its registry entry intact.
        if (shopExisted && preEvent.isCancelled()) {
            event.setCancelled(true);
            return;
        }

        if (preEvent.getOutcome().shouldBreakSign()) {
            event.setCancelled(true);
            signBlock.breakNaturally();
            ChestShop.logDebug("Shop sign creation at " + sign.getLocation() + " by "
                    + event.getPlayer().getName() + " was cancelled (creation outcome: " + preEvent.getOutcome()
                    + ") and the sign broken");
            return;
        }

        for (byte i = 0; i < preEvent.getSignLines().length && i < 4; ++i) {
            SignText.setLine(event, i, preEvent.getSignLine(i));
        }

        if (preEvent.isCancelled()) {
            ChestShop.logDebug("Shop sign creation at " + sign.getLocation() + " by "
                    + event.getPlayer().getName() + " was cancelled (creation outcome: " + preEvent.getOutcome()
                    + ") and sign lines were set to " + String.join(", ", preEvent.getSignLines()));
            return;
        }

        CreatedShop postEvent = new CreatedShop(
                preEvent.getPlayer(),
                preEvent.getSign(),
                shopBlockService.findConnectedContainer(preEvent.getSign()),
                preEvent.getSignLines(),
                preEvent.getOwnerAccount());
        if (shopExisted) {
            shops.onEdited(postEvent);
        } else {
            shops.onCreated(postEvent);
        }
    }
}
