package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.services.ShopBlockService;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.utils.BlockUtil;
import io.paradaux.chestshop.ChestShop;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.model.DestroyedShop;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.chestshop.services.ShopService;
import io.paradaux.chestshop.services.SignBreakService;
import io.paradaux.chestshop.services.SignService;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import static io.paradaux.chestshop.utils.BlockUtil.getState;
import static io.paradaux.chestshop.utils.BlockUtil.getAttachedBlock;
import static io.paradaux.chestshop.utils.BlockUtil.isSign;
import static io.paradaux.chestshop.utils.Permissions.OTHER_NAME_DESTROY;

/**
 * The shop-sign break business logic, extracted from {@code SignBreakListener}
 * (chestshop/structure/0002). The Bukkit event handlers stay in the listener; this service owns
 * the protection/removal decisions shared with {@code TransactionService} and the physics/sign-create
 * listeners.
 *
 * @author Acrobot
 */
@Singleton
public class SignBreakServiceImpl implements SignBreakService {
    private static final BlockFace[] SIGN_CONNECTION_FACES = {BlockFace.SOUTH, BlockFace.NORTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP};
    public static final String METADATA_NAME = "shop_destroyer";

    private final AccountService accounts;
    private final ShopService shops;
    private final ChestShopConfiguration config;
    private final SignService signService;
    private final ShopBlockService shopBlockService;

    @Inject
    public SignBreakServiceImpl(AccountService accounts, ShopService shops, ChestShopConfiguration config,
                                SignService signService, ShopBlockService shopBlockService) {
        this.accounts = accounts;
        this.shops = shops;
        this.config = config;
        this.signService = signService;
        this.shopBlockService = shopBlockService;
    }

    @Override
    public void handlePhysicsBreak(Block block) {
        if (!BlockUtil.isSign(block)) {
            return;
        }

        Sign sign = (Sign) getState(block, false);
        Block attachedBlock = BlockUtil.getAttachedBlock(sign);

        if (attachedBlock.getType() == Material.AIR && signService.isValid(sign)) {
            sendShopDestroyed((Sign) block.getState(), block.hasMetadata(METADATA_NAME)
                    ? (Player) block.getMetadata(METADATA_NAME).get(0).value()
                    : null);
        }
    }

    @Override
    public boolean canBlockBeBroken(Block block, Player breaker) {
        List<Sign> attachedSigns = getAttachedSigns(block);
        List<Sign> brokenBlocks = new LinkedList<Sign>();

        boolean canBeBroken = true;

        for (Sign sign : attachedSigns) {

            if (!canBeBroken || !signService.isValid(sign)) {
                continue;
            }

            if (config.isTurnOffSignProtection() || canDestroyShop(breaker, SignService.getOwner(sign))) {
                brokenBlocks.add(sign);
            } else {
                canBeBroken = false;
            }
        }

        if (!canBeBroken) {
            return false;
        }

        for (Sign sign : brokenBlocks) {
            sign.setMetadata(METADATA_NAME, new FixedMetadataValue(ChestShop.getPlugin(), breaker));
        }

        return true;
    }

    private boolean canDestroyShop(Player player, String name) {
        return player != null && accounts.canUseName(player, OTHER_NAME_DESTROY, name);
    }

    @Override
    public void sendShopDestroyed(Sign sign, Player player) {
        Container connectedContainer = shopBlockService.findConnectedContainer(sign.getBlock());

        shops.onDestroyed(new DestroyedShop(player, sign, connectedContainer));
    }

    private static List<Sign> getAttachedSigns(Block block) {
        if (block == null) {
            return new ArrayList<>();
        }

        if (isSign(block)) {
            return Collections.singletonList((Sign) block.getState());
        } else {
            List<Sign> attachedSigns = new LinkedList<Sign>();

            for (BlockFace face : SIGN_CONNECTION_FACES) {
                Block relative = block.getRelative(face);

                if (!isSign(relative)) {
                    continue;
                }

                Sign sign = (Sign) relative.getState();

                if (getAttachedBlock(sign).equals(block)) {
                    attachedSigns.add(sign);
                }
            }

            return attachedSigns;
        }
    }
}
