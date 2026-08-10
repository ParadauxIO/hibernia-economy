package io.paradaux.treasuryrestapi.mapper;

import io.paradaux.treasuryrestapi.model.ChestShopItemRow;
import io.paradaux.treasuryrestapi.model.ChestShopItemStatsRow;
import io.paradaux.treasuryrestapi.model.ChestShopMarketStatsRow;
import io.paradaux.treasuryrestapi.model.ChestShopPriceDayRow;
import io.paradaux.treasuryrestapi.model.ChestShopShopRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Read-only access to the ChestShop analytics tables ({@code chestshop_shop},
 * {@code chestshop_sale}) that back the public market endpoints.
 *
 * <p>Every query is shaped to ride the V6 indexes:
 * <ul>
 *   <li>shop directory filters on {@code idx_shop_item}/{@code idx_shop_mat}
 *       ({@code item_key|material, active}) or {@code idx_shop_firm};</li>
 *   <li>item aggregates group by {@code item_key} (clustered into
 *       {@code idx_cs_item});</li>
 *   <li>item windows filter {@code (item_key, occurred_at)} on
 *       {@code idx_cs_item}.</li>
 * </ul>
 *
 * <p>Time windows are passed as a precomputed {@code since} timestamp rather than
 * an inline {@code INTERVAL ? DAY} so the value binds as a plain parameter
 * (portable across drivers) and the query plan stays stable.
 *
 * <p>Nullable filter params use the {@code (#{p} IS NULL OR col = #{p})} idiom so
 * one prepared statement serves every filter combination.
 */
@Mapper
public interface ChestShopMapper {

    // ── live shop directory (chestshop_shop) ────────────────────────────────────

    /**
     * Active shops matching the (all-optional) filters, newest-priced first.
     * Ordering: in-stock before sold-out, then priced-to-buy before not, then
     * cheapest buy price, then most-recently-seen. Owner name is resolved from
     * {@code firm} (BUSINESS) or {@code economy_players} (PERSONAL); null otherwise.
     */
    @Select("""
            SELECT s.shop_id, s.world, s.sign_x, s.sign_y, s.sign_z,
                   s.is_admin_shop AS adminShop, s.shop_account_type, s.shop_firm_id,
                   s.shop_owner_uuid_bin AS shopOwnerUuid,
                   COALESCE(f.display_name, fp.current_name) AS ownerName,
                   s.material, s.item_key, s.item_name, s.item_custom,
                   s.buy_price, s.sell_price, s.batch_qty, s.current_stock, s.stock_at, s.last_seen
            FROM chestshop_shop s
            LEFT JOIN firm f ON f.firm_id = s.shop_firm_id
            LEFT JOIN economy_players fp ON fp.player_uuid_bin = s.shop_owner_uuid_bin
            WHERE s.active = 1
              AND (#{itemKey}  IS NULL OR s.item_key = #{itemKey})
              AND (#{material} IS NULL OR s.material = #{material})
              AND (#{firmId}   IS NULL OR s.shop_firm_id = #{firmId})
              AND (#{buyable} = 0 OR s.buy_price IS NOT NULL)
              AND (#{inStock} = 0 OR s.is_admin_shop = 1 OR s.current_stock > 0)
              AND (#{search} IS NULL
                   OR s.item_name LIKE CONCAT('%', #{search}, '%')
                   OR s.material  LIKE CONCAT('%', #{search}, '%'))
            ORDER BY (s.current_stock IS NOT NULL AND s.current_stock = 0) ASC,
                     (s.buy_price IS NULL) ASC,
                     s.buy_price ASC,
                     s.last_seen DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<ChestShopShopRow> listShops(@Param("itemKey") String itemKey,
                                     @Param("material") String material,
                                     @Param("firmId") Integer firmId,
                                     @Param("buyable") int buyable,
                                     @Param("inStock") int inStock,
                                     @Param("search") String search,
                                     @Param("limit") int limit,
                                     @Param("offset") int offset);

    @Select("""
            SELECT COUNT(*)
            FROM chestshop_shop s
            WHERE s.active = 1
              AND (#{itemKey}  IS NULL OR s.item_key = #{itemKey})
              AND (#{material} IS NULL OR s.material = #{material})
              AND (#{firmId}   IS NULL OR s.shop_firm_id = #{firmId})
              AND (#{buyable} = 0 OR s.buy_price IS NOT NULL)
              AND (#{inStock} = 0 OR s.is_admin_shop = 1 OR s.current_stock > 0)
              AND (#{search} IS NULL
                   OR s.item_name LIKE CONCAT('%', #{search}, '%')
                   OR s.material  LIKE CONCAT('%', #{search}, '%'))
            """)
    long countShops(@Param("itemKey") String itemKey,
                    @Param("material") String material,
                    @Param("firmId") Integer firmId,
                    @Param("buyable") int buyable,
                    @Param("inStock") int inStock,
                    @Param("search") String search);

    // ── item directory (chestshop_sale, grouped) ────────────────────────────────

    @Select("""
            SELECT item_key,
                   MAX(material)    AS material,
                   MAX(item_name)   AS item_name,
                   MAX(item_custom) AS item_custom,
                   COUNT(*)                       AS trade_count,
                   COALESCE(SUM(quantity), 0)     AS total_quantity,
                   COALESCE(SUM(total_price), 0.00) AS total_volume
            FROM chestshop_sale
            WHERE (#{search} IS NULL
                   OR item_name LIKE CONCAT('%', #{search}, '%')
                   OR material  LIKE CONCAT('%', #{search}, '%')
                   OR item_key  LIKE CONCAT('%', #{search}, '%'))
            GROUP BY item_key
            ORDER BY trade_count DESC, item_name ASC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<ChestShopItemRow> listItems(@Param("search") String search,
                                     @Param("limit") int limit,
                                     @Param("offset") int offset);

    @Select("""
            SELECT COUNT(DISTINCT item_key)
            FROM chestshop_sale
            WHERE (#{search} IS NULL
                   OR item_name LIKE CONCAT('%', #{search}, '%')
                   OR material  LIKE CONCAT('%', #{search}, '%')
                   OR item_key  LIKE CONCAT('%', #{search}, '%'))
            """)
    long countItems(@Param("search") String search);

    // ── per-item detail (chestshop_sale, windowed) ──────────────────────────────

    /**
     * Windowed stats for one item plus its lifetime trade count. Returns a row
     * even when the item has no sales at all (zeros + null label + 0 allTime),
     * so the service can distinguish "exists, quiet" from "never traded".
     */
    @Select("""
            SELECT #{itemKey} AS item_key,
                   MAX(material)    AS material,
                   MAX(item_name)   AS item_name,
                   COALESCE(MAX(item_custom), 0) AS item_custom,
                   COALESCE(SUM(CASE WHEN occurred_at >= #{since} THEN 1 ELSE 0 END), 0)          AS trade_count,
                   COALESCE(SUM(CASE WHEN occurred_at >= #{since} THEN quantity ELSE 0 END), 0)   AS total_quantity,
                   COALESCE(SUM(CASE WHEN occurred_at >= #{since} THEN total_price ELSE 0 END), 0.00) AS total_volume,
                   COUNT(*) AS all_time_trades
            FROM chestshop_sale
            WHERE item_key = #{itemKey}
            """)
    ChestShopItemStatsRow itemStats(@Param("itemKey") String itemKey,
                                    @Param("since") LocalDateTime since);

    /** Count of active live shops trading this item (used for the 404 check). */
    @Select("SELECT COUNT(*) FROM chestshop_shop WHERE item_key = #{itemKey} AND active = 1")
    long countActiveShopsForItem(@Param("itemKey") String itemKey);

    @Select("""
            SELECT DATE_FORMAT(DATE(occurred_at), '%Y-%m-%d') AS day,
                   COUNT(*)                       AS sales,
                   COALESCE(SUM(quantity), 0)     AS total_quantity,
                   COALESCE(SUM(total_price), 0.00) AS total_volume,
                   CASE WHEN SUM(quantity) > 0 THEN SUM(total_price) / SUM(quantity) ELSE 0 END AS avg_unit_price
            FROM chestshop_sale
            WHERE item_key = #{itemKey} AND occurred_at >= #{since}
            GROUP BY DATE(occurred_at)
            ORDER BY day ASC
            """)
    List<ChestShopPriceDayRow> itemPriceByDay(@Param("itemKey") String itemKey,
                                              @Param("since") LocalDateTime since);

    // ── global market stats ─────────────────────────────────────────────────────

    @Select("""
            SELECT COUNT(*)                       AS total_sales,
                   COALESCE(SUM(total_price), 0.00) AS total_volume,
                   COUNT(DISTINCT item_key)       AS distinct_items
            FROM chestshop_sale
            """)
    ChestShopMarketStatsRow marketStats();

    @Select("SELECT COUNT(*) FROM chestshop_shop WHERE active = 1")
    long countActiveShops();
}
