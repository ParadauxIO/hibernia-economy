package io.paradaux.chestshop.services;

import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.inventory.InventoryHolder;
import java.util.List;

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
public interface ShopBlockService {

    Sign getConnectedSign(Block block);

    Container findConnectedContainer(Sign sign);

    Container findConnectedContainer(Block block);

    List<Sign> findConnectedShopSigns(InventoryHolder chestShopInventoryHolder);

    List<Sign> findConnectedShopSigns(Block chestBlock);

    Sign findAnyNearbyShopSign(Block block);

    boolean couldBeShopContainer(Block block);

    boolean couldBeShopContainer(InventoryHolder holder);

    boolean isShopBlock(Block block);

    boolean isShopBlock(InventoryHolder holder);
}
