package io.paradaux.chestshop.mappers;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** Durable failed-reversal evidence in ChestShop's local users.db (ADT-135). */
public interface TradeRecoveryMapper {
    @Update("""
            CREATE TABLE IF NOT EXISTS trade_recovery (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                trade_id TEXT NOT NULL,
                recorded_at TEXT NOT NULL,
                evidence TEXT NOT NULL
            )
            """)
    void createTable();

    @Insert("INSERT INTO trade_recovery(trade_id, recorded_at, evidence) VALUES(#{tradeId}, #{recordedAt}, #{evidence})")
    void insert(@Param("tradeId") String tradeId, @Param("recordedAt") String recordedAt,
                @Param("evidence") String evidence);
}
