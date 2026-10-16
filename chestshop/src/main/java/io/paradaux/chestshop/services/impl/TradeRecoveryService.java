package io.paradaux.chestshop.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.mappers.TradeRecoveryMapper;
import io.paradaux.chestshop.model.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;

import java.time.Instant;
import java.util.Arrays;

/** Records evidence only: reconciliation must not automatically repeat goods or money moves. */
@Singleton
@Slf4j
public class TradeRecoveryService {
    private final TradeRecoveryMapper mapper;

    @Inject
    public TradeRecoveryService(TradeRecoveryMapper mapper) {
        this.mapper = mapper;
    }

    public void record(Transaction trade, RuntimeException reversalFailure) {
        String evidence = "<snapshot unavailable>";
        try {
            YamlConfiguration snapshot = new YamlConfiguration();
            snapshot.set("version", 1);
            snapshot.set("reason", "GOODS_REVERSAL_FAILED_AFTER_SETTLEMENT_FAILURE");
            snapshot.set("player.uuid", trade.getClient().getUniqueId().toString());
            snapshot.set("player.name", trade.getClient().getName());
            snapshot.set("owner.uuid", trade.getOwnerAccount().getUuid().toString());
            snapshot.set("owner.name", trade.getOwnerAccount().getName());
            snapshot.set("direction", trade.getTransactionType().name());
            snapshot.set("unlimited-owner", trade.isUnlimitedOwner());
            snapshot.set("price", trade.getExactPrice().toPlainString());
            snapshot.set("settlement-txn-id", trade.getSettlementTxnId());
            if (trade.getSign() != null) {
                Location location = trade.getSign().getLocation();
                if (location.getWorld() != null) {
                    snapshot.set("sign.world-uuid", location.getWorld().getUID().toString());
                    snapshot.set("sign.world-name", location.getWorld().getName());
                }
                snapshot.set("sign.x", location.getBlockX());
                snapshot.set("sign.y", location.getBlockY());
                snapshot.set("sign.z", location.getBlockZ());
            }
            // Bukkit serialization preserves stack counts and item metadata, not just material names.
            snapshot.set("items", Arrays.asList(trade.getStock()));
            if (reversalFailure != null) {
                snapshot.set("exception", reversalFailure.toString());
            }
            evidence = snapshot.saveToString();
            mapper.insert(trade.getTradeId().toString(), Instant.now().toString(), evidence);
            log.warn("ChestShop recovery required: tradeId={} audit=users.db/trade_recovery", trade.getTradeId(), reversalFailure);
        } catch (RuntimeException auditFailure) {
            // Persistence must not prevent cancellation. Preserve evidence in the server log as a last resort.
            log.error("ChestShop recovery audit write failed: tradeId={} evidence=\n{}",
                    trade.getTradeId(), evidence, auditFailure);
        }
    }
}
