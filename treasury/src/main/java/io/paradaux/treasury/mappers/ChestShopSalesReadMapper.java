package io.paradaux.treasury.mappers;

import io.paradaux.treasury.api.market.SaleRow;
import io.paradaux.treasury.api.market.SalesQuery;
import io.paradaux.treasury.api.market.SalesSummary;
import io.paradaux.treasury.api.market.TopCustomer;
import io.paradaux.treasury.api.market.TopItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Read side of the ChestShop sales tracker ({@code chestshop_sale}), backing the
 * in-game sales commands (PAR-176). {@link ChestShopMarketMapper} is write-only;
 * this is reads only. Optional scope/filters use dynamic {@code <where>}/{@code <if>}
 * so a null filter is simply omitted (and so a null UUID is never bound).
 * {@code mapUnderscoreToCamelCase} + the column aliases map rows onto the API DTOs;
 * UUID columns bind via the registered UuidBinaryTypeHandler.
 */
@Mapper
public interface ChestShopSalesReadMapper {

    // Shared predicate fragment — kept identical across the methods below.
    String WHERE = """
            <where>
              <if test="q.firmId != null">AND s.shop_firm_id = #{q.firmId}</if>
              <if test="q.ownerUuid != null">AND s.shop_owner_uuid_bin = #{q.ownerUuid}</if>
              <if test="q.accountId != null">AND s.shop_account_id = #{q.accountId}</if>
              <if test="since != null">AND s.occurred_at &gt;= #{since}</if>
              <if test="q.direction != null">AND s.direction = #{q.direction}</if>
              <if test="q.itemKey != null">AND s.item_key = #{q.itemKey}</if>
              <if test="q.customerUuid != null">AND s.customer_uuid_bin = #{q.customerUuid}</if>
            </where>
            """;

    @Select("""
            <script>
            SELECT s.occurred_at  AS occurredAt,
                   s.direction    AS direction,
                   s.customer_uuid_bin AS customerUuid,
                   fp.current_name AS customerName,
                   s.quantity     AS quantity,
                   s.material     AS material,
                   s.item_name    AS itemName,
                   s.item_custom  AS itemCustom,
                   s.unit_price   AS unitPrice,
                   s.total_price  AS totalPrice,
                   s.tax_amount   AS taxAmount,
                   s.world        AS world,
                   s.sign_x       AS signX,
                   s.sign_y       AS signY,
                   s.sign_z       AS signZ,
                   s.txn_id       AS txnId
            FROM chestshop_sale s
            LEFT JOIN economy_players fp ON fp.player_uuid_bin = s.customer_uuid_bin
            """ + WHERE + """
            ORDER BY s.occurred_at DESC, s.sale_id DESC
            LIMIT #{q.limit} OFFSET #{q.offset}
            </script>
            """)
    List<SaleRow> listSales(@Param("q") SalesQuery q, @Param("since") LocalDateTime since);

    @Select("""
            <script>
            SELECT COUNT(*) FROM chestshop_sale s
            """ + WHERE + """
            </script>
            """)
    long countSales(@Param("q") SalesQuery q, @Param("since") LocalDateTime since);

    @Select("""
            <script>
            SELECT COUNT(*)                                   AS saleCount,
                   COALESCE(SUM(s.quantity), 0)               AS totalUnits,
                   COALESCE(SUM(s.total_price), 0.00)         AS totalVolume,
                   COALESCE(SUM(s.tax_amount), 0.00)          AS totalTax,
                   COALESCE(SUM(s.direction = 'BUY'), 0)      AS buyCount,
                   COALESCE(SUM(CASE WHEN s.direction = 'BUY'  THEN s.total_price ELSE 0 END), 0.00) AS buyVolume,
                   COALESCE(SUM(s.direction = 'SELL'), 0)     AS sellCount,
                   COALESCE(SUM(CASE WHEN s.direction = 'SELL' THEN s.total_price ELSE 0 END), 0.00) AS sellVolume
            FROM chestshop_sale s
            """ + WHERE + """
            </script>
            """)
    SalesSummary summarizeTotals(@Param("q") SalesQuery q, @Param("since") LocalDateTime since);

    @Select("""
            <script>
            SELECT s.item_key               AS itemKey,
                   MAX(s.item_name)         AS itemName,
                   COALESCE(SUM(s.quantity), 0)    AS units,
                   COALESCE(SUM(s.total_price), 0.00) AS volume,
                   COUNT(*)                 AS saleCount
            FROM chestshop_sale s
            """ + WHERE + """
            GROUP BY s.item_key
            ORDER BY units DESC, saleCount DESC
            LIMIT #{topN}
            </script>
            """)
    List<TopItem> topItems(@Param("q") SalesQuery q, @Param("since") LocalDateTime since,
                           @Param("topN") int topN);

    @Select("""
            <script>
            SELECT s.customer_uuid_bin      AS customerUuid,
                   MAX(fp.current_name)     AS customerName,
                   COUNT(*)                 AS saleCount,
                   COALESCE(SUM(s.total_price), 0.00) AS volume
            FROM chestshop_sale s
            LEFT JOIN economy_players fp ON fp.player_uuid_bin = s.customer_uuid_bin
            """ + WHERE + """
            GROUP BY s.customer_uuid_bin
            ORDER BY saleCount DESC, volume DESC
            LIMIT #{topN}
            </script>
            """)
    List<TopCustomer> topCustomers(@Param("q") SalesQuery q, @Param("since") LocalDateTime since,
                                   @Param("topN") int topN);
}
