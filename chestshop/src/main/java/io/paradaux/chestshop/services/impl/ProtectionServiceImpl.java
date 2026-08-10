package io.paradaux.chestshop.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.model.BuildPermission;
import io.paradaux.chestshop.model.ProtectionCheck;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.chestshop.services.ProtectionService;
import io.paradaux.chestshop.services.ShopBlockService;
import io.paradaux.chestshop.utils.BlockUtil;
import io.paradaux.chestshop.utils.Permissions;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;

import javax.annotation.Nullable;
import java.util.function.Consumer;

import static io.paradaux.chestshop.utils.BlockUtil.getState;
import static io.paradaux.chestshop.utils.BlockUtil.isSign;

/**
 * Owns all shop/chest protection checks: the vanilla shop-member check (absorbed from the former
 * {@code VanillaShopProtection}) runs first, then the optional WorldGuard/GriefPrevention providers
 * the integrations register as method refs. Also carries the caller-facing access/view/
 * sign-placement checks the former {@code Security} facade exposed (PAR-316).
 */
@Singleton
public class ProtectionServiceImpl implements ProtectionService {

    private static final BlockFace[] SIGN_CONNECTION_FACES = {BlockFace.UP, BlockFace.EAST, BlockFace.WEST, BlockFace.NORTH, BlockFace.SOUTH};
    private static final BlockFace[] BLOCKS_AROUND = {BlockFace.UP, BlockFace.DOWN, BlockFace.EAST, BlockFace.WEST, BlockFace.NORTH, BlockFace.SOUTH};

    private final AccountService accounts;
    private final ChestShopConfiguration config;
    private final SignService signService;
    private final ShopBlockService shopBlockService;

    @Nullable private volatile Consumer<ProtectionCheck> worldGuardProtection;
    @Nullable private volatile Consumer<BuildPermission> worldGuardBuilding;
    @Nullable private volatile Consumer<BuildPermission> griefPreventionBuilding;

    @Inject
    public ProtectionServiceImpl(AccountService accounts, ChestShopConfiguration config,
                                 SignService signService, ShopBlockService shopBlockService) {
        this.accounts = accounts;
        this.config = config;
        this.signService = signService;
        this.shopBlockService = shopBlockService;
    }

    @Override
    public void setWorldGuardProtection(Consumer<ProtectionCheck> check) {
        this.worldGuardProtection = check;
    }

    @Override
    public void setWorldGuardBuilding(Consumer<BuildPermission> check) {
        this.worldGuardBuilding = check;
    }

    @Override
    public void setGriefPreventionBuilding(Consumer<BuildPermission> check) {
        this.griefPreventionBuilding = check;
    }

    @Override
    public boolean canAccess(Block block, Player player, boolean ignoreBuiltInProtection) {
        return runProtectionCheck(new ProtectionCheck(block, player, ignoreBuiltInProtection));
    }

    @Override
    public boolean canAccess(Player player, Block block) {
        return canAccess(block, player, false);
    }

    @Override
    public boolean canView(Block block, Player player, boolean ignoreBuiltInProtection) {
        return runProtectionCheck(new ProtectionCheck(block, player, ignoreBuiltInProtection, false));
    }

    private boolean runProtectionCheck(ProtectionCheck event) {
        // Vanilla ChestShop shop-member protection always runs first; both handlers only ever
        // set DENY (and self-guard on it), so the result is "deny if either denies".
        vanillaCheck(event);
        Consumer<ProtectionCheck> wg = worldGuardProtection;
        if (wg != null) {
            wg.accept(event);
        }
        return event.getResult() != Event.Result.DENY;
    }

    @Override
    public boolean canBuild(Player player, @Nullable Location chest, Location sign) {
        BuildPermission event = new BuildPermission(player, chest, sign);
        Consumer<BuildPermission> wgb = worldGuardBuilding;
        if (wgb != null && event.isAllowed()) {
            wgb.accept(event);
        }
        Consumer<BuildPermission> gpb = griefPreventionBuilding;
        if (gpb != null && event.isAllowed()) {
            gpb.accept(event);
        }
        return event.isAllowed();
    }

    // ---- vanilla shop-member protection (was VanillaShopProtection) --------------

    private void vanillaCheck(ProtectionCheck event) {
        if (event.getResult() == Event.Result.DENY || event.isBuiltInProtectionIgnored()) {
            return;
        }
        if (!hasMemberAccess(event.getPlayer(), event.getBlock())) {
            event.setResult(Event.Result.DENY);
        }
    }

    private boolean hasMemberAccess(Player player, Block block) {
        if (!isSign(block) && !shopBlockService.couldBeShopContainer(block)) {
            return true;
        }

        if (isSign(block)) {
            Sign sign = (Sign) getState(block, false);
            if (!signService.isValid(sign)) {
                return true;
            }
            if (!isShopMember(player, sign)) {
                return false;
            }
        }

        if (shopBlockService.couldBeShopContainer(block)) {
            Sign sign = shopBlockService.getConnectedSign(block);
            if (sign != null && !isShopMember(player, sign)) {
                return false;
            }
        }

        return true;
    }

    private boolean isShopMember(Player player, Sign sign) {
        return accounts.hasPermission(player, Permissions.OTHER_NAME_ACCESS, sign);
    }

    // ---- shop-sign placement (was Security) -------------------------------------

    @Override
    public boolean canPlaceSign(Player player, Sign sign) {
        Block baseBlock = BlockUtil.getAttachedBlock(sign);

        if (!config.isAllowMultipleShopsAtOneBlock() && anotherShopFound(baseBlock, sign.getBlock(), player)) {
            return false;
        }

        return canBePlaced(player, sign.getBlock());
    }

    private boolean canBePlaced(Player player, Block sign) {
        for (BlockFace face : BLOCKS_AROUND) {
            Block block = sign.getRelative(face);
            if (!shopBlockService.couldBeShopContainer(block)) {
                continue;
            }
            if (!canAccess(player, block)) {
                return false;
            }
        }
        return true;
    }

    private boolean anotherShopFound(Block baseBlock, Block signBlock, Player player) {
        for (BlockFace face : SIGN_CONNECTION_FACES) {
            Block block = baseBlock.getRelative(face);
            if (block.equals(signBlock) || !BlockUtil.isSign(block)) {
                continue;
            }
            Sign sign = (Sign) getState(block, false);
            if (!signService.isValid(sign) || !BlockUtil.getAttachedBlock(sign).equals(baseBlock)) {
                continue;
            }
            if (!accounts.isOwner(player, sign)) {
                return true;
            }
        }
        return false;
    }
}
