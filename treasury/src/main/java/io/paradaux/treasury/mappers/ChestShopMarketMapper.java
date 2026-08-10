package io.paradaux.treasury.mappers;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Map;
import java.util.UUID;

/**
 * Writes the ChestShop sales tracker + live shop registry (V6:
 * {@code chestshop_sale}, {@code chestshop_shop}). Called by {@code MarketApiImpl}
 * on behalf of ChestShop-3. Insert/upsert take a pre-built parameter map (UUID
 * values bind via the global {@code UuidBinaryTypeHandler}); jdbcType is pinned
 * on nullable columns so NULLs bind cleanly.
 */
@Mapper
public interface ChestShopMarketMapper {

    @Insert("""
            INSERT INTO chestshop_sale
              (txn_id, direction, customer_uuid_bin, shop_account_id, shop_account_type,
               shop_firm_id, shop_owner_uuid_bin, is_admin_shop, material, item_key, item_name,
               item_custom, item_data, quantity, unit_price, total_price, tax_amount,
               world, sign_x, sign_y, sign_z, shop_stock)
            VALUES
              (#{txnId,jdbcType=BIGINT}, #{direction}, #{customerUuid}, #{shopAccountId,jdbcType=INTEGER},
               #{shopAccountType,jdbcType=VARCHAR}, #{shopFirmId,jdbcType=INTEGER},
               #{shopOwnerUuid,jdbcType=BINARY}, #{adminShop}, #{material}, #{itemKey}, #{itemName},
               #{itemCustom}, #{itemData,jdbcType=LONGVARCHAR}, #{quantity}, #{unitPrice}, #{totalPrice},
               #{taxAmount}, #{world,jdbcType=VARCHAR}, #{signX,jdbcType=INTEGER}, #{signY,jdbcType=INTEGER},
               #{signZ,jdbcType=INTEGER}, #{shopStock,jdbcType=INTEGER})
            """)
    void insertSale(Map<String, Object> p);

    // visible/hologram are owner-controlled (set via the toggle writes below) and so
    // are deliberately NOT in the column list — a create/restock/price upsert must not
    // reset an owner's choices. On INSERT they take the schema DEFAULT 1; on UPDATE the
    // ON DUPLICATE KEY clause leaves them untouched.
    @Insert("""
            INSERT INTO chestshop_shop
              (world, world_uuid, sign_x, sign_y, sign_z, is_admin_shop, shop_account_id, shop_account_type,
               shop_firm_id, shop_owner_uuid_bin, material, item_key, item_name, item_custom, item_data,
               buy_price, sell_price, batch_qty, current_stock, estimated_capacity, stock_at, active)
            VALUES
              (#{world}, #{worldUuid,jdbcType=BINARY}, #{signX}, #{signY}, #{signZ}, #{adminShop},
               #{shopAccountId,jdbcType=INTEGER}, #{shopAccountType,jdbcType=VARCHAR}, #{shopFirmId,jdbcType=INTEGER},
               #{shopOwnerUuid,jdbcType=BINARY}, #{material}, #{itemKey}, #{itemName}, #{itemCustom},
               #{itemData,jdbcType=LONGVARCHAR}, #{buyPrice,jdbcType=DECIMAL}, #{sellPrice,jdbcType=DECIMAL},
               #{batchQty}, #{currentStock,jdbcType=INTEGER}, #{estimatedCapacity,jdbcType=INTEGER},
               CASE WHEN #{currentStock,jdbcType=INTEGER} IS NULL THEN NULL ELSE NOW() END, 1)
            ON DUPLICATE KEY UPDATE
               world_uuid = VALUES(world_uuid),
               is_admin_shop = VALUES(is_admin_shop),
               shop_account_id = VALUES(shop_account_id),
               shop_account_type = VALUES(shop_account_type),
               shop_firm_id = VALUES(shop_firm_id),
               shop_owner_uuid_bin = VALUES(shop_owner_uuid_bin),
               material = VALUES(material), item_key = VALUES(item_key), item_name = VALUES(item_name),
               item_custom = VALUES(item_custom), item_data = VALUES(item_data),
               buy_price = VALUES(buy_price), sell_price = VALUES(sell_price), batch_qty = VALUES(batch_qty),
               current_stock = VALUES(current_stock), estimated_capacity = VALUES(estimated_capacity),
               stock_at = CASE WHEN VALUES(current_stock) IS NULL THEN stock_at ELSE NOW() END,
               active = 1
            """)
    void upsertShop(Map<String, Object> p);

    @Update("""
            UPDATE chestshop_shop SET active = 0
             WHERE world = #{world} AND sign_x = #{x} AND sign_y = #{y} AND sign_z = #{z}
            """)
    void deactivateShop(@Param("world") String world, @Param("x") int x,
                        @Param("y") int y, @Param("z") int z);

    @Update("""
            UPDATE chestshop_shop
               SET current_stock = #{stock,jdbcType=INTEGER},
                   estimated_capacity = #{capacity,jdbcType=INTEGER},
                   stock_at = NOW()
             WHERE world = #{world} AND sign_x = #{x} AND sign_y = #{y} AND sign_z = #{z}
            """)
    void updateShopStock(@Param("world") String world, @Param("x") int x, @Param("y") int y,
                         @Param("z") int z, @Param("stock") Integer stock,
                         @Param("capacity") Integer capacity);

    @Update("""
            UPDATE chestshop_shop SET visible = #{visible}
             WHERE world = #{world} AND sign_x = #{x} AND sign_y = #{y} AND sign_z = #{z}
            """)
    void setShopVisibility(@Param("world") String world, @Param("x") int x, @Param("y") int y,
                           @Param("z") int z, @Param("visible") boolean visible);

    @Update("""
            UPDATE chestshop_shop SET hologram = #{hologram}
             WHERE world = #{world} AND sign_x = #{x} AND sign_y = #{y} AND sign_z = #{z}
            """)
    void setShopHologram(@Param("world") String world, @Param("x") int x, @Param("y") int y,
                         @Param("z") int z, @Param("hologram") boolean hologram);

    @Insert("""
            INSERT INTO chestshop_preview_preference (player_uuid_bin, visible)
            VALUES (#{playerUuid,jdbcType=BINARY}, #{visible})
            ON DUPLICATE KEY UPDATE visible = VALUES(visible)
            """)
    void upsertPreviewPreference(@Param("playerUuid") UUID playerUuid, @Param("visible") boolean visible);
}
