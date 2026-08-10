package io.paradaux.treasury.mappers;

import io.paradaux.treasury.api.market.ShopLocation;
import io.paradaux.treasury.api.market.ShopResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/**
 * Read side of the live shop registry ({@code chestshop_shop}), backing the
 * in-ChestShop {@code /find} search + hologram loader (PAR-166 epic).
 * {@link ChestShopMarketMapper} is write-only; this is reads only.
 *
 * <p>Owner name is resolved at query time — firm display name (BUSINESS), IGN
 * (PERSONAL), else the account display name — and is null for admin / unknown.
 * Columns are aliased to the {@link ShopResult} record components so MyBatis maps
 * rows by constructor; UUID columns bind via the registered UuidBinaryTypeHandler.
 */
@Mapper
public interface ShopQueryMapper {

    // Shared projection — aliased to ShopResult's record components.
    String SHOP_COLUMNS = """
            s.world AS world,
            s.world_uuid AS worldUuid,
            s.sign_x AS signX, s.sign_y AS signY, s.sign_z AS signZ,
            s.is_admin_shop AS adminShop,
            s.shop_account_type AS shopAccountType,
            s.shop_firm_id AS shopFirmId,
            s.shop_owner_uuid_bin AS shopOwnerUuid,
            COALESCE(f.display_name, fp.current_name, a.display_name) AS ownerName,
            s.material AS material,
            s.item_key AS itemKey,
            s.item_name AS itemName,
            s.item_custom AS itemCustom,
            s.item_data AS itemData,
            s.buy_price AS buyPrice,
            s.sell_price AS sellPrice,
            s.batch_qty AS batchQty,
            s.current_stock AS currentStock,
            s.estimated_capacity AS estimatedCapacity,
            s.visible AS visible,
            s.hologram AS hologram
            """;

    String OWNER_JOINS = """
            LEFT JOIN firm f ON f.firm_id = s.shop_firm_id
            LEFT JOIN economy_players fp ON fp.player_uuid_bin = s.shop_owner_uuid_bin
            LEFT JOIN accounts a ON a.account_id = s.shop_account_id
            """;

    /** Active, owner-visible shops for an item — exact or fuzzy (substring) match. */
    @Select("""
            <script>
            SELECT\s""" + SHOP_COLUMNS + """
            FROM chestshop_shop s
            """ + OWNER_JOINS + """
            WHERE s.active = 1 AND s.visible = 1
              <choose>
                <when test="fuzzy">AND s.item_key LIKE CONCAT('%', #{itemKey}, '%')</when>
                <otherwise>AND s.item_key = #{itemKey}</otherwise>
              </choose>
              <if test="world != null">AND s.world = #{world}</if>
            ORDER BY s.last_seen DESC
            LIMIT #{limit}
            </script>
            """)
    List<ShopResult> searchShops(@Param("itemKey") String itemKey, @Param("fuzzy") boolean fuzzy,
                                 @Param("world") String world, @Param("limit") int limit);

    /**
     * Active shops whose sign sits in a chunk's block range. Includes hidden shops
     * (search visibility is separate from the hologram flag the caller honours).
     */
    @Select("""
            SELECT\s""" + SHOP_COLUMNS + """
            FROM chestshop_shop s
            """ + OWNER_JOINS + """
            WHERE s.active = 1
              AND s.world = #{world}
              AND s.sign_x BETWEEN #{minX} AND #{maxX}
              AND s.sign_z BETWEEN #{minZ} AND #{maxZ}
            """)
    List<ShopResult> shopsInChunk(@Param("world") String world,
                                  @Param("minX") int minX, @Param("maxX") int maxX,
                                  @Param("minZ") int minZ, @Param("maxZ") int maxZ);

    /** Locations of all active shop signs, optionally scoped to one world. */
    @Select("""
            <script>
            SELECT s.world AS world, s.world_uuid AS worldUuid,
                   s.sign_x AS signX, s.sign_y AS signY, s.sign_z AS signZ
            FROM chestshop_shop s
            WHERE s.active = 1
              <if test="world != null">AND s.world = #{world}</if>
            </script>
            """)
    List<ShopLocation> activeShopLocations(@Param("world") String world);

    /** Distinct active item keys containing the substring (case-insensitive), for tab-complete. */
    @Select("""
            SELECT DISTINCT s.item_key
            FROM chestshop_shop s
            WHERE s.active = 1 AND s.visible = 1
              AND s.item_key LIKE CONCAT('%', #{substring}, '%')
            ORDER BY s.item_key ASC
            LIMIT #{limit}
            """)
    List<String> matchingItemKeys(@Param("substring") String substring, @Param("limit") int limit);

    /** Stored hologram preference for a player, or null if none. */
    @Select("SELECT visible FROM chestshop_preview_preference WHERE player_uuid_bin = #{playerUuid,jdbcType=BINARY}")
    Boolean previewPreference(@Param("playerUuid") UUID playerUuid);
}
