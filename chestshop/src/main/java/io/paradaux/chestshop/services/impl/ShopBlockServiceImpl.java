package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.utils.InventoryUtil;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.chestshop.services.ShopBlockService;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.utils.BlockUtil;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.inventory.InventoryHolder;

import java.util.ArrayList;
import java.util.List;

import static io.paradaux.chestshop.utils.InventoryUtil.getLeftSide;
import static io.paradaux.chestshop.utils.InventoryUtil.getRightSide;
import static io.paradaux.chestshop.utils.BlockUtil.getState;

/**
 * The stateful block↔shop-sign geometry split out of {@code ShopBlockUtil} (PAR-282):
 * resolving the sign a container is attached to (and vice-versa), and deciding whether a
 * block/holder is a shop container (config {@code SHOP_CONTAINERS}). The block-level
 * {@code isShopBlock}/{@code isShopChest} predicates moved here off {@link SignService} —
 * they are block detection, not sign-line logic, and hosting them here lets this service
 * depend on {@link SignService} <em>directly</em> (for {@link SignService#isValid})
 * with no back-edge, so the former {@code ShopBlockUtil ↔ SignService} construction
 * cycle (and its {@code Provider<>} work-around) is gone. The pure block geometry
 * (findNeighbor, the face arrays) stays static on {@link ShopBlockUtil}.
 *
 * @author Acrobot
 */
@Singleton
public class ShopBlockServiceImpl implements ShopBlockService {

    private final ChestShopConfiguration config;
    private final SignService signService;

    @Inject
    public ShopBlockServiceImpl(ChestShopConfiguration config, SignService signService) {
        this.config = config;
        this.signService = signService;
    }

    @Override
    public Sign getConnectedSign(Block block) {
        Sign sign = findAnyNearbyShopSign(block);

        if (sign == null) {
            Block neighbor = BlockUtil.findNeighbor(block);
            if (neighbor != null) {
                sign = findAnyNearbyShopSign(neighbor);
            }
        }

        return sign;
    }

    @Override
    public Container findConnectedContainer(Sign sign) {
        if (!BlockUtil.isLoaded(sign.getBlock())) {
            return null;
        }

        BlockFace signFace = null;
        BlockData data = sign.getBlockData();
        if (data instanceof WallSign) {
            signFace = ((WallSign) data).getFacing().getOppositeFace();
        }
        return findConnectedContainer(sign.getLocation(), signFace);
    }

    @Override
    public Container findConnectedContainer(Block block) {
        if (!BlockUtil.isLoaded(block)) {
            return null;
        }

        BlockFace signFace = null;
        BlockData data = block.getBlockData();
        if (data instanceof WallSign) {
            signFace = ((WallSign) data).getFacing().getOppositeFace();
        }
        return findConnectedContainer(block.getLocation(), signFace);
    }

    private Container findConnectedContainer(Location location, BlockFace signFace) {
        if (signFace != null) {
            Block faceBlock = location.clone().add(signFace.getModX(), signFace.getModY(), signFace.getModZ()).getBlock();
            if (couldBeShopContainer(faceBlock)) {
                return (Container) faceBlock.getState();
            }
        }

        for (BlockFace bf : BlockUtil.SHOP_FACES) {
            if (bf != signFace) {
                Block faceBlock = location.clone().add(bf.getModX(), bf.getModY(), bf.getModZ()).getBlock();
                if (couldBeShopContainer(faceBlock)) {
                    return (Container) faceBlock.getState();
                }
            }
        }
        return null;
    }

    @Override
    public List<Sign> findConnectedShopSigns(InventoryHolder chestShopInventoryHolder) {
        List<Sign> result = new ArrayList<>();

        if (chestShopInventoryHolder instanceof DoubleChest) {
            InventoryHolder leftChestSide = getLeftSide((DoubleChest) chestShopInventoryHolder, false);
            InventoryHolder rightChestSide = getRightSide((DoubleChest) chestShopInventoryHolder, false);

            if (!(leftChestSide instanceof BlockState) || !(rightChestSide instanceof BlockState)) {
                return result;
            }

            Block leftChest = ((BlockState) leftChestSide).getBlock();
            Block rightChest = ((BlockState) rightChestSide).getBlock();

            if (isShopBlock(leftChest)) {
                result.addAll(findConnectedShopSigns(leftChest));
            }

            if (isShopBlock(rightChest)) {
                result.addAll(findConnectedShopSigns(rightChest));
            }
        }

        else if (chestShopInventoryHolder instanceof BlockState) {
            Block chestBlock = ((BlockState) chestShopInventoryHolder).getBlock();

            if (isShopBlock(chestBlock)) {
                result.addAll(findConnectedShopSigns(chestBlock));
            }
        }

        return result;
    }

    @Override
    public List<Sign> findConnectedShopSigns(Block chestBlock) {
        List<Sign> result = new ArrayList<>();

        for (BlockFace bf : BlockUtil.SHOP_FACES) {
            Block faceBlock = chestBlock.getRelative(bf);

            if (!BlockUtil.isSign(faceBlock)) {
                continue;
            }

            Sign sign = (Sign) faceBlock.getState();

            Container signContainer = findConnectedContainer(sign);
            if (signContainer == null || !chestBlock.equals(signContainer.getBlock())) {
                continue;
            }

            if (signService.isValid(sign)) {
                result.add(sign);
            }
        }

        return result;
    }

    @Override
    public Sign findAnyNearbyShopSign(Block block) {
        for (BlockFace bf : BlockUtil.SHOP_FACES) {
            Block faceBlock = block.getRelative(bf);
            if (!BlockUtil.isLoaded(faceBlock)) {
                continue;
            }

            BlockData data = faceBlock.getBlockData();
            if (data instanceof WallSign) {
                if (((WallSign) data).getFacing() != bf
                        && couldBeShopContainer(faceBlock.getRelative(((WallSign) data).getFacing().getOppositeFace()))) {
                    continue;
                }
            } else if (!(data instanceof org.bukkit.block.data.type.Sign)) {
                continue;
            }

            Sign sign = (Sign) getState(faceBlock, false);

            if (signService.isValid(sign)) {
                return sign;
            }
        }
        return null;
    }

    @Override
    public boolean couldBeShopContainer(Block block) {
        return block != null && BlockUtil.isLoaded(block) && config.getShopContainers().contains(block.getType());
    }

    @Override
    public boolean couldBeShopContainer(InventoryHolder holder) {
        return (holder instanceof Container && couldBeShopContainer(((Container) holder).getBlock()))
                    || (holder instanceof DoubleChest && couldBeShopContainer(getLeftSide((DoubleChest) holder, false)));
    }

    @Override
    public boolean isShopBlock(Block block) {
        if (!couldBeShopContainer(block)) {
            return false;
        }

        return getConnectedSign(block) != null;
    }

    @Override
    public boolean isShopBlock(InventoryHolder holder) {
        if (holder instanceof DoubleChest) {
            return isShopBlock(InventoryUtil.getLeftSide((DoubleChest) holder, false))
                    || isShopBlock(InventoryUtil.getRightSide((DoubleChest) holder, false));
        } else if (holder instanceof BlockState) {
            return isShopBlock(((BlockState) holder).getBlock());
        }
        return false;
    }
}
