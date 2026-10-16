package io.paradaux.chestshop.services.impl;

import com.google.inject.Guice;
import io.paradaux.chestshop.guice.DatabaseModule;
import io.paradaux.chestshop.guice.ChestShopDatabases;
import io.paradaux.chestshop.mappers.TradeRecoveryMapper;
import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.model.Transaction;
import io.paradaux.chestshop.support.ServerTest;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class TradeRecoveryServiceTest extends ServerTest {
    @TempDir Path directory;

    private Transaction trade() {
        Transaction trade = mock(Transaction.class);
        when(trade.getTradeId()).thenReturn(UUID.randomUUID());
        when(trade.getClient()).thenReturn(player("Buyer"));
        when(trade.getOwnerAccount()).thenReturn(new Account("Owner", UUID.randomUUID()));
        when(trade.getTransactionType()).thenReturn(Transaction.TransactionType.BUY);
        when(trade.getExactPrice()).thenReturn(new BigDecimal("12.50"));
        ItemStack stock = item(Material.DIAMOND, 5);
        var meta = stock.getItemMeta();
        meta.setDisplayName("Recovery gem");
        stock.setItemMeta(meta);
        when(trade.getStock()).thenReturn(new ItemStack[]{stock});
        return trade;
    }

    @Test
    void evidenceSurvivesClosingAndReopeningDatabase() throws Exception {
        var users = directory.resolve("users.db").toFile();
        var injector = Guice.createInjector(new DatabaseModule(users, directory.resolve("items.db").toFile()));
        Transaction trade = trade();
        Sign sign = mock(Sign.class);
        var world = server.addSimpleWorld("shops");
        when(sign.getLocation()).thenReturn(new Location(world, 10, 64, -20));
        when(trade.getSign()).thenReturn(sign);
        new TradeRecoveryService(injector.getInstance(TradeRecoveryMapper.class)).record(trade, null);
        injector.getInstance(ChestShopDatabases.class).close();

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + users);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT * FROM trade_recovery")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("trade_id")).isEqualTo(trade.getTradeId().toString());
            assertThat(Instant.parse(rows.getString("recorded_at"))).isBeforeOrEqualTo(Instant.now());
            var evidence = new YamlConfiguration();
            evidence.loadFromString(rows.getString("evidence"));
            assertThat(evidence.getString("player.uuid")).isEqualTo(trade.getClient().getUniqueId().toString());
            assertThat(evidence.getString("owner.uuid")).isEqualTo(trade.getOwnerAccount().getUuid().toString());
            assertThat(evidence.getString("price")).isEqualTo("12.50");
            assertThat(evidence.getString("sign.world-uuid")).isEqualTo(world.getUID().toString());
            assertThat(evidence.getInt("sign.z")).isEqualTo(-20);
            assertThat(evidence.getList("items")).isEqualTo(java.util.Arrays.asList(trade.getStock()));
            assertThat(rows.next()).isFalse();
        }
    }

    @Test
    void recordsCoordinatesWithoutALoadedWorld() {
        TradeRecoveryMapper mapper = mock(TradeRecoveryMapper.class);
        Transaction trade = trade();
        Sign sign = mock(Sign.class);
        when(sign.getLocation()).thenReturn(new Location(null, 1, 2, 3));
        when(trade.getSign()).thenReturn(sign);
        new TradeRecoveryService(mapper).record(trade, null);
        verify(mapper).insert(eq(trade.getTradeId().toString()), anyString(), contains("z: 3"));
    }

    @Test
    void recordsMissingLocationAndExceptionAndDoesNotThrowOnDatabaseFailure() {
        TradeRecoveryMapper mapper = mock(TradeRecoveryMapper.class);
        Transaction trade = trade();
        when(trade.isUnlimitedOwner()).thenReturn(true);
        doThrow(new IllegalStateException("disk full")).when(mapper).insert(anyString(), anyString(), anyString());
        assertThatCode(() -> new TradeRecoveryService(mapper).record(trade, new IllegalStateException("reverse failed")))
                .doesNotThrowAnyException();
        verify(mapper).insert(eq(trade.getTradeId().toString()), anyString(), contains("reverse failed"));
    }
}
