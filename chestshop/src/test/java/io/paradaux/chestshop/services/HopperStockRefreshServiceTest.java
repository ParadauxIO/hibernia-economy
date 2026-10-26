package io.paradaux.chestshop.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.paradaux.chestshop.listeners.ItemMoveListener;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.impl.InventoryServiceImpl;
import io.paradaux.chestshop.services.impl.MaterialServiceImpl;
import io.paradaux.chestshop.services.impl.ShopBlockServiceImpl;
import io.paradaux.chestshop.services.impl.StockCounterServiceImpl;
import io.paradaux.chestshop.support.ServerTest;
import io.paradaux.chestshop.support.TestConfigs;
import io.paradaux.chestshop.utils.InventoryUtil;
import io.paradaux.chestshop.utils.SignText;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

/** Real inventory/geometry/counter checks; item-code persistence is replaced by a fixed diamond parser. */
class HopperStockRefreshServiceTest extends ServerTest {
    private ChestShopConfiguration config;
    private World world;
    private JavaPlugin plugin;
    private ShopBlockService blocks;
    private StockCounterService counters;
    private SignService signs;
    private HopperStockRefreshService refresh;
    private ShopStockPublisher stock;
    private MockedStatic<InventoryUtil> inventoryApi;
    private final List<BlockState> containers = new ArrayList<>();
    private final AtomicInteger enumerations = new AtomicInteger();

    @BeforeEach
    void setup() {
        // MockBukkit has no getHolder(boolean); its no-arg holder is the live test container.
        inventoryApi = mockStatic(InventoryUtil.class, CALLS_REAL_METHODS);
        inventoryApi
                .when(() -> InventoryUtil.getHolder(any(Inventory.class), eq(false)))
                .thenAnswer(call -> ((Inventory) call.getArgument(0)).getHolder());
        plugin = spy(MockBukkit.createMockPlugin());
        world = spy(server.addSimpleWorld("hopper"));
        Server lookup = mock(Server.class, org.mockito.AdditionalAnswers.delegatesTo(server));
        var worldId = world.getUID();
        doReturn(world).when(lookup).getWorld(worldId);
        doReturn(lookup).when(plugin).getServer();
        // MockBukkit also lacks tile-entity enumeration; provide only that platform boundary.
        doAnswer(call -> {
                    int x = call.getArgument(0);
                    int z = call.getArgument(1);
                    Chunk chunk = mock(org.mockbukkit.mockbukkit.world.ChunkMock.class);
                    when(chunk.getTileEntities(false))
                            .thenAnswer(ignored -> enumerations.incrementAndGet() > 0
                                    ? containers.stream()
                                            .filter(state -> (state.getX() >> 4) == x && (state.getZ() >> 4) == z)
                                            .toArray(BlockState[]::new)
                                    : null);
                    return chunk;
                })
                .when(world)
                .getChunkAt(anyInt(), anyInt());
        world.loadChunk(0, 0);
        config = TestConfigs.with(TestConfigs.defaults(), "useStockCounter", true);
        TestConfigs.with(config, "shopContainersRaw", List.of("CHEST"));
        signs = new SignService(config);
        blocks = spy(new ShopBlockServiceImpl(config, signs));
        ItemService items = mock(ItemService.class);
        when(items.parse("Diamond")).thenReturn(item(Material.DIAMOND, 1));
        MaterialService materials = new MaterialServiceImpl(config);
        counters = spy(new StockCounterServiceImpl(
                items, config, signs, blocks, new InventoryServiceImpl(config, materials), materials));
        stock = mock(ShopStockPublisher.class);
        refresh = new HopperStockRefreshService(plugin, config, blocks, signs, counters, stock, () -> 0L);
    }

    @AfterEach
    void closeApiShim() {
        inventoryApi.close();
    }

    private Inventory chestAt(int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(Material.CHEST);
        containers.add(block.getState());
        return ((Container) block.getState()).getInventory();
    }

    private Block signAt(int x, int y, int z, BlockFace facing) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(Material.OAK_WALL_SIGN);
        WallSign data = (WallSign) block.getBlockData();
        data.setFacing(facing);
        block.setBlockData(data);
        Sign sign = (Sign) block.getState();
        SignText.setLine(sign, 0, "Notch");
        SignText.setLine(sign, 1, "1");
        SignText.setLine(sign, 2, "B 5");
        SignText.setLine(sign, 3, "Diamond");
        sign.update();
        return block;
    }

    private String counter(Block sign) {
        return SignText.getLine((Sign) sign.getState(), 1);
    }

    private void ticks(int count) {
        for (int i = 0; i < count; i++) refresh.drain();
    }

    @Test
    void burstReadsFinalInventoryOnceAndUpdatesEveryAttachedSign() {
        Inventory inventory = chestAt(4, 64, 4);
        Block east = signAt(5, 64, 4, BlockFace.EAST);
        Block west = signAt(3, 64, 4, BlockFace.WEST);
        inventory.addItem(item(Material.DIAMOND, 10));
        for (int i = 0; i < 10_000; i++) refresh.markDirty(inventory);
        verifyNoInteractions(counters);
        verify(blocks, never()).findConnectedShopSigns(any(InventoryHolder.class));
        ticks(99);
        assertThat(counter(east)).isEqualTo("1");
        inventory.setItem(0, item(Material.DIAMOND, 7));
        ticks(1);
        assertThat(counter(east)).isEqualTo("Q 1 : C 7");
        assertThat(counter(west)).isEqualTo("Q 1 : C 7");
        verify(blocks, times(1)).findConnectedShopSigns(any(InventoryHolder.class));
    }

    @Test
    void moveRefreshesBothInventoriesWithoutPredictingFailedInsertion() {
        Inventory source = chestAt(4, 64, 4);
        Block sourceSign = signAt(5, 64, 4, BlockFace.EAST);
        Inventory destination = chestAt(8, 64, 4);
        Block destinationSign = signAt(9, 64, 4, BlockFace.EAST);
        source.addItem(item(Material.DIAMOND, 10));
        destination.addItem(item(Material.DIAMOND, 2));
        ItemMoveListener listener = new ItemMoveListener(refresh, config, blocks);
        InventoryMoveItemEvent event = new InventoryMoveItemEvent(source, item(Material.DIAMOND, 3), destination, true);
        listener.onStockMove(event);
        // Simulate completion after event dispatch, independently of the event's claimed amount.
        source.setItem(0, item(Material.DIAMOND, 9));
        destination.setItem(0, item(Material.DIAMOND, 3));
        ticks(200);
        assertThat(counter(sourceSign)).isEqualTo("Q 1 : C 9");
        assertThat(counter(destinationSign)).isEqualTo("Q 1 : C 3");
        listener.onStockMove(event); // failed insertion: neither inventory changes
        ticks(200);
        assertThat(counter(sourceSign)).isEqualTo("Q 1 : C 9");
        assertThat(counter(destinationSign)).isEqualTo("Q 1 : C 3");
    }

    @Test
    void discoversShopCreatedAfterEnqueueAndDoesNotResurrectRemovedSign() {
        Inventory inventory = chestAt(4, 64, 4);
        inventory.addItem(item(Material.DIAMOND, 12));
        refresh.markDirty(inventory);
        Block sign = signAt(5, 64, 4, BlockFace.EAST);
        ticks(100);
        assertThat(counter(sign)).isEqualTo("Q 1 : C 12");
        refresh.markDirty(inventory);
        sign.setType(Material.AIR);
        ticks(100);
        assertThat(sign.getType()).isEqualTo(Material.AIR);
        verify(counters, times(1)).updateCounterOnQuantityLine(any(), any());
    }

    @Test
    void unloadDiscardsCoordinatesAndReloadReconcilesWithoutAnotherTransfer() {
        Inventory inventory = chestAt(4, 64, 4);
        Block sign = signAt(5, 64, 4, BlockFace.EAST);
        inventory.addItem(item(Material.DIAMOND, 12));
        refresh.markDirty(inventory);
        refresh.unloadChunk(world.getUID(), 0, 0);
        ticks(100);
        assertThat(counter(sign)).isEqualTo("1");
        refresh.reconcileChunk(world.getUID(), 0, 0);
        ticks(110);
        assertThat(counter(sign)).isEqualTo("Q 1 : C 12");
    }

    @Test
    void chunkLoadAloneQueuesNoWork() {
        // PAR-544: exploring players must not build a recount backlog of unrelated chunks.
        Inventory inventory = chestAt(4, 64, 4);
        signAt(5, 64, 4, BlockFace.EAST);
        inventory.addItem(item(Material.DIAMOND, 12));
        refresh.loadChunk(world.getUID(), 0, 0);
        ticks(200);
        assertThat(enumerations).hasValue(0);
        verifyNoInteractions(counters);
    }

    @Test
    void unloadDefersDroppedWorkUntilThatChunkLoadsAgain() {
        Inventory inventory = chestAt(4, 64, 4);
        Block sign = signAt(5, 64, 4, BlockFace.EAST);
        inventory.addItem(item(Material.DIAMOND, 12));
        refresh.markDirty(inventory);
        refresh.unloadChunk(world.getUID(), 0, 0);
        ticks(200);
        assertThat(counter(sign)).isEqualTo("1");
        refresh.loadChunk(world.getUID(), 1, 0); // a different chunk restores nothing
        ticks(200);
        assertThat(counter(sign)).isEqualTo("1");
        refresh.loadChunk(world.getUID(), 0, 0);
        ticks(100);
        assertThat(counter(sign)).isEqualTo("Q 1 : C 12");
        // Only the dropped coordinate came back: no whole-chunk enumeration.
        assertThat(enumerations).hasValue(0);
        refresh.loadChunk(world.getUID(), 0, 0); // restored once, not on every later load
        ticks(200);
        verify(counters, times(1)).updateCounterOnQuantityLine(any(), any());
    }

    @Test
    void unloadDuringOverflowRecountsTheWholeChunkOnLoad() {
        Inventory inventory = chestAt(4, 64, 4);
        Block sign = signAt(5, 64, 4, BlockFace.EAST);
        inventory.addItem(item(Material.DIAMOND, 12));
        refresh.reconcileChunk(world.getUID(), 0, 0);
        refresh.unloadChunk(world.getUID(), 0, 0);
        ticks(200);
        assertThat(counter(sign)).isEqualTo("1");
        refresh.loadChunk(world.getUID(), 0, 0);
        ticks(110);
        assertThat(counter(sign)).isEqualTo("Q 1 : C 12");
    }

    @Test
    void worldUnloadDropsDeferredWork() {
        Inventory inventory = chestAt(4, 64, 4);
        Block sign = signAt(5, 64, 4, BlockFace.EAST);
        inventory.addItem(item(Material.DIAMOND, 12));
        refresh.markDirty(inventory);
        refresh.unloadChunk(world.getUID(), 0, 0);
        refresh.unloadWorld(world.getUID());
        refresh.loadChunk(world.getUID(), 0, 0);
        ticks(200);
        assertThat(counter(sign)).isEqualTo("1");
    }

    @Test
    void publishesRegistryStockEvenWithCountersOffButNeverForAdminShops() {
        // PAR-544: /find stock follows hoppers whether or not sign counters are enabled.
        when(stock.enabled()).thenReturn(true);
        TestConfigs.with(config, "useStockCounter", false);
        Inventory inventory = chestAt(4, 64, 4);
        Block signBlock = signAt(5, 64, 4, BlockFace.EAST);
        refresh.markDirty(inventory);
        ticks(100);
        verify(stock, times(1)).publish(any(Sign.class), any(Inventory.class));
        verifyNoInteractions(counters);
        assertThat(counter(signBlock)).isEqualTo("1");

        Sign sign = (Sign) signBlock.getState();
        SignText.setLine(sign, 0, config.getAdminShopName());
        sign.update();
        refresh.markDirty(inventory);
        ticks(100);
        verify(stock, times(1)).publish(any(Sign.class), any(Inventory.class));
    }

    @Test
    void stopFlushesQueuedRegistryStock() {
        refresh.stop();
        verify(stock).flush();
    }

    @Test
    void disabledCountersAndUnlimitedAdminShopsAreNotUpdated() {
        Inventory inventory = chestAt(4, 64, 4);
        Block signBlock = signAt(5, 64, 4, BlockFace.EAST);
        refresh.markDirty(inventory);
        TestConfigs.with(config, "useStockCounter", false);
        ticks(100);
        assertThat(counter(signBlock)).isEqualTo("1");
        TestConfigs.with(config, "useStockCounter", true);
        TestConfigs.with(config, "forceUnlimitedAdminShop", true);
        Sign sign = (Sign) signBlock.getState();
        SignText.setLine(sign, 0, config.getAdminShopName());
        sign.update();
        refresh.markDirty(inventory);
        ticks(100);
        assertThat(counter(signBlock)).isEqualTo("1");
        verifyNoInteractions(counters);
    }

    @Test
    void schedulerStartsOnceAndStopDropsPendingWork() {
        Inventory inventory = chestAt(4, 64, 4);
        Block sign = signAt(5, 64, 4, BlockFace.EAST);
        refresh.start();
        refresh.start();
        refresh.markDirty(inventory);
        server.getScheduler().performTicks(99);
        assertThat(counter(sign)).isEqualTo("1");
        server.getScheduler().performTicks(1);
        assertThat(counter(sign)).isEqualTo("Q 1 : C 0");
        inventory.addItem(item(Material.DIAMOND, 10));
        refresh.markDirty(inventory);
        refresh.stop();
        server.getScheduler().performTicks(200);
        assertThat(counter(sign)).isEqualTo("Q 1 : C 0");
    }

    @Test
    void overflowReconcilesFinalStockWhileNormalQueueRemainsBusy() {
        // Fill the coordinate queue cheaply, without manufacturing thousands of inventories.
        Container holder = mock(Container.class);
        when(holder.getWorld()).thenReturn(world);
        AtomicInteger coordinate = new AtomicInteger();
        when(holder.getX()).thenAnswer(ignored -> coordinate.get() & 15);
        when(holder.getZ()).thenAnswer(ignored -> (coordinate.get() >> 4) & 15);
        when(holder.getY()).thenAnswer(ignored -> coordinate.get() >> 8);
        Inventory busy = mock(Inventory.class);
        when(busy.getHolder()).thenReturn(holder);
        for (int i = 0; i < HopperStockRefreshService.MAX_PENDING; i++) {
            coordinate.set(i);
            refresh.markDirty(busy);
        }
        Inventory overflow = chestAt(4, 64, 4);
        Block sign = signAt(5, 64, 4, BlockFace.EAST);
        overflow.addItem(item(Material.DIAMOND, 17));
        refresh.markDirty(overflow);
        ticks(99);
        clearInvocations(world);
        ticks(1);
        // The first 32 ordinary entries are at y=0; recovery is at y=64.
        verify(world, times(32)).getBlockAt(anyInt(), eq(0), anyInt());
        assertThat(counter(sign)).isEqualTo("Q 1 : C 17");
    }

    @Test
    void liveProtectionCancelsBeforeAnyDeferredRefresh() {
        Inventory source = chestAt(4, 64, 4);
        signAt(5, 64, 4, BlockFace.EAST);
        Inventory cart = chest(27); // non-block destination, as with a minecart
        var event = new InventoryMoveItemEvent(source, item(Material.DIAMOND, 1), cart, false);
        var listener = new ItemMoveListener(refresh, config, blocks);
        server.getPluginManager().registerEvents(listener, plugin);
        server.getPluginManager().callEvent(event);
        assertThat(event.isCancelled()).isTrue();
        ticks(200);
        verifyNoInteractions(counters);
        TestConfigs.with(config, "turnOffHopperProtection", true);
        var allowed = new InventoryMoveItemEvent(source, item(Material.DIAMOND, 1), cart, false);
        server.getPluginManager().callEvent(allowed);
        assertThat(allowed.isCancelled()).isFalse();
        ticks(200);
        verify(counters, times(1)).updateCounterOnQuantityLine(any(), any());
    }

    @Test
    void busyContainerDoesNotPostponeOlderPendingWork() {
        TestConfigs.with(config, "hopperStockRefreshIntervalTicks", 20);
        Inventory inventory = chestAt(4, 64, 4);
        Block sign = signAt(5, 64, 4, BlockFace.EAST);
        for (int i = 1; i <= 20; i++) {
            inventory.setItem(0, item(Material.DIAMOND, i));
            refresh.markDirty(inventory);
            ticks(1);
        }
        assertThat(counter(sign)).isEqualTo("Q 1 : C 20");
    }

    @Test
    void largeBatchIsPacedAndOverflowDoesNotRefreshTheSameSignsTwiceInOneTick() {
        List<Block> shopSigns = new ArrayList<>();
        for (int i = 0; i < 65; i++) {
            Inventory inventory = chestAt(4, 32 + i, 4);
            shopSigns.add(signAt(5, 32 + i, 4, BlockFace.EAST));
            refresh.markDirty(inventory);
        }
        // Include a non-container tile entity in the platform enumeration.
        containers.add(shopSigns.getFirst().getState());
        refresh.reconcileChunk(world.getUID(), 0, 0);
        ticks(100);
        verify(counters, times(32)).updateCounterOnQuantityLine(any(), any());
        assertThat(counter(shopSigns.get(32))).isEqualTo("1");
        ticks(1);
        verify(counters, times(64)).updateCounterOnQuantityLine(any(), any());
        ticks(1);
        assertThat(counter(shopSigns.getLast())).isEqualTo("Q 1 : C 0");
        verify(counters, times(65)).updateCounterOnQuantityLine(any(), any());
    }

    @Test
    void timeBudgetDefersWorkWithoutLosingIt() {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        refresh = new HopperStockRefreshService(
                plugin, config, blocks, signs, counters, stock, () -> clock.getAndAdd(500_000));
        Inventory first = chestAt(4, 64, 4);
        Block firstSign = signAt(5, 64, 4, BlockFace.EAST);
        Inventory second = chestAt(4, 68, 4);
        Block secondSign = signAt(5, 68, 4, BlockFace.EAST);
        refresh.markDirty(first);
        refresh.markDirty(second);
        refresh.reconcileChunk(world.getUID(), 0, 0);
        ticks(100);
        assertThat(counter(firstSign)).isEqualTo("Q 1 : C 0");
        assertThat(counter(secondSign)).isEqualTo("1");
        ticks(1);
        assertThat(counter(secondSign)).isEqualTo("Q 1 : C 0");
    }

    @Test
    void worldUnloadClearsNormalAndPartiallyDrainedRecoveryWork() {
        List<Block> shopSigns = new ArrayList<>();
        for (int i = 0; i < 34; i++) {
            Inventory inventory = chestAt(4, 32 + i, 4);
            shopSigns.add(signAt(5, 32 + i, 4, BlockFace.EAST));
            refresh.markDirty(inventory);
        }
        refresh.reconcileChunk(world.getUID(), 0, 0);
        ticks(100); // two positions remain in both queues
        refresh.reconcileChunk(world.getUID(), 0, 0);
        refresh.unloadWorld(java.util.UUID.randomUUID()); // unrelated world must not discard work
        refresh.unloadWorld(world.getUID());
        ticks(200);
        assertThat(counter(shopSigns.getLast())).isEqualTo("1");
        verify(counters, times(32)).updateCounterOnQuantityLine(any(), any());
    }

    @Test
    void unloadedChunksAreNeverLoadedByNormalOrRecoveryWork() {
        Inventory inventory = chestAt(4, 64, 4);
        refresh.markDirty(inventory);
        refresh.reconcileChunk(world.getUID(), 0, 0);
        doReturn(false).when(world).isChunkLoaded(0, 0);
        clearInvocations(world);
        ticks(100);
        verify(world, never()).getChunkAt(anyInt(), anyInt());
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        verifyNoInteractions(counters);
    }

    @Test
    void missingWorldAndRemovedContainerAreDiscardedSafely() {
        Inventory inventory = chestAt(4, 64, 4);
        refresh.markDirty(inventory);
        world.getBlockAt(4, 64, 4).setType(Material.AIR);
        ticks(100);
        verifyNoInteractions(counters);
        refresh.markDirty(inventory);
        refresh.reconcileChunk(world.getUID(), 0, 0);
        var worldId = world.getUID();
        Server lookup = plugin.getServer();
        doReturn(null).when(lookup).getWorld(worldId);
        ticks(100);
        verifyNoInteractions(counters);
    }

    @Test
    void disabledOrOversizedCounterConfigurationDoesNotEvenReadInventories() {
        Inventory inventory = mock(Inventory.class);
        TestConfigs.with(config, "useStockCounter", false);
        refresh.markDirty(inventory);
        refresh.reconcileChunk(world.getUID(), 0, 0);
        TestConfigs.with(config, "useStockCounter", true);
        TestConfigs.with(config, "maxShopAmount", 100000);
        refresh.markDirty(inventory);
        ticks(100);
        verifyNoInteractions(inventory, counters);
        refresh.stop(); // safe even before start
    }

    @Test
    void queuedDoubleChestStillRefreshesSurvivingHalfAfterSplit() {
        Inventory left = chestAt(4, 64, 4);
        Inventory right = chestAt(6, 64, 4);
        Block rightSign = signAt(7, 64, 4, BlockFace.EAST);
        right.addItem(item(Material.DIAMOND, 11));
        var doubleChest = mock(org.bukkit.block.DoubleChest.class);
        InventoryHolder leftHolder = left.getHolder();
        InventoryHolder rightHolder = right.getHolder();
        when(doubleChest.getLeftSide(false)).thenReturn(leftHolder);
        when(doubleChest.getRightSide(false)).thenReturn(rightHolder);
        Inventory combined = mock(Inventory.class);
        when(combined.getHolder()).thenReturn(doubleChest);
        refresh.markDirty(combined);
        world.getBlockAt(4, 64, 4).setType(Material.AIR);
        ticks(100);
        assertThat(counter(rightSign)).isEqualTo("Q 1 : C 11");
    }

    @Test
    void increasingIntervalOnReloadDelaysPendingNormalAndRecoveryWork() {
        Inventory inventory = chestAt(4, 64, 4);
        Block sign = signAt(5, 64, 4, BlockFace.EAST);
        refresh.markDirty(inventory);
        refresh.reconcileChunk(world.getUID(), 0, 0);
        ticks(50);
        TestConfigs.with(config, "hopperStockRefreshIntervalTicks", 150);
        ticks(99);
        assertThat(counter(sign)).isEqualTo("1");
        ticks(1);
        assertThat(counter(sign)).isEqualTo("Q 1 : C 0");
        verify(counters, times(1)).updateCounterOnQuantityLine(any(), any());
    }

    @Test
    void reducingIntervalOnReloadDoesNotLeaveNewWorkBehindAnOldDeadline() {
        Inventory first = chestAt(4, 64, 4);
        Block firstSign = signAt(5, 64, 4, BlockFace.EAST);
        refresh.markDirty(first);
        ticks(50);
        TestConfigs.with(config, "hopperStockRefreshIntervalTicks", 10);
        Inventory second = chestAt(4, 68, 4);
        Block secondSign = signAt(5, 68, 4, BlockFace.EAST);
        refresh.markDirty(second);
        ticks(1);
        assertThat(counter(firstSign)).isEqualTo("Q 1 : C 0");
        assertThat(counter(secondSign)).isEqualTo("1");
        ticks(8);
        assertThat(counter(secondSign)).isEqualTo("1");
        ticks(1);
        assertThat(counter(secondSign)).isEqualTo("Q 1 : C 0");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -100})
    void invalidIntervalStillDefersWorkUntilNextTick(int interval) {
        TestConfigs.with(config, "hopperStockRefreshIntervalTicks", interval);
        Inventory inventory = chestAt(4, 64, 4);
        Block sign = signAt(5, 64, 4, BlockFace.EAST);
        refresh.markDirty(inventory);
        refresh.reconcileChunk(world.getUID(), 0, 0);
        assertThat(counter(sign)).isEqualTo("1");
        ticks(1);
        assertThat(counter(sign)).isEqualTo("Q 1 : C 0");
        verify(counters, times(1)).updateCounterOnQuantityLine(any(), any());
    }

    @Test
    void chunkLoadWhileDisabledDiscardsDeferredWork() {
        Inventory inventory = chestAt(4, 64, 4);
        Block sign = signAt(5, 64, 4, BlockFace.EAST);
        inventory.addItem(item(Material.DIAMOND, 12));
        refresh.markDirty(inventory);
        refresh.unloadChunk(world.getUID(), 0, 0);
        TestConfigs.with(config, "useStockCounter", false);
        refresh.loadChunk(world.getUID(), 0, 0);
        TestConfigs.with(config, "useStockCounter", true);
        refresh.loadChunk(world.getUID(), 0, 0);
        ticks(200);
        assertThat(counter(sign)).isEqualTo("1");
    }

    @Test
    void unloadOfAnUnknownWorldOrEmptyChunkDefersNothing() {
        refresh.unloadChunk(java.util.UUID.randomUUID(), 0, 0);
        refresh.unloadChunk(world.getUID(), 3, 3);
        refresh.loadChunk(world.getUID(), 3, 3);
        ticks(200);
        assertThat(enumerations).hasValue(0);
        verifyNoInteractions(counters);
    }

    @Test
    void unloadMidRecoveryDefersOnlyThatChunksRemainingContainers() {
        AtomicLong clock = new AtomicLong();
        // Every timing read exhausts the budget: one container per phase per tick.
        refresh = new HopperStockRefreshService(
                plugin, config, blocks, signs, counters, stock, () -> clock.getAndAdd(500_000));
        Inventory first = chestAt(4, 64, 4);
        Block firstSign = signAt(5, 64, 4, BlockFace.EAST);
        Inventory second = chestAt(8, 64, 4);
        Block secondSign = signAt(9, 64, 4, BlockFace.EAST);
        first.addItem(item(Material.DIAMOND, 3));
        second.addItem(item(Material.DIAMOND, 4));
        world.loadChunk(1, 0);
        Inventory elsewhere = chestAt(20, 64, 4);
        refresh.reconcileChunk(world.getUID(), 0, 0);
        ticks(100); // recovery enumerates chunk 0 and refreshes one container
        refresh.markDirty(elsewhere); // pending work in another chunk
        refresh.unloadChunk(world.getUID(), 5, 5); // unrelated: recovery and pending untouched
        refresh.unloadChunk(world.getUID(), 0, 0);
        ticks(200);
        String before = counter(firstSign) + "|" + counter(secondSign);
        assertThat(before).containsOnlyOnce("Q 1 : C");
        refresh.loadChunk(world.getUID(), 0, 0);
        ticks(100);
        assertThat(counter(firstSign)).isEqualTo("Q 1 : C 3");
        assertThat(counter(secondSign)).isEqualTo("Q 1 : C 4");
    }

    @Test
    void deferredPositionsPastTheCapRecountTheWholeChunk() {
        Container holder = mock(Container.class);
        when(holder.getWorld()).thenReturn(world);
        AtomicInteger coordinate = new AtomicInteger();
        when(holder.getX()).thenAnswer(ignored -> coordinate.get() & 15);
        when(holder.getZ()).thenAnswer(ignored -> (coordinate.get() >> 4) & 15);
        when(holder.getY()).thenAnswer(ignored -> coordinate.get() >> 8);
        Inventory busy = mock(Inventory.class);
        when(busy.getHolder()).thenReturn(holder);
        for (int i = 0; i < HopperStockRefreshService.MAX_PENDING; i++) {
            coordinate.set(i);
            refresh.markDirty(busy);
        }
        refresh.unloadChunk(world.getUID(), 0, 0); // fills the deferred-position allowance
        world.loadChunk(1, 0);
        Inventory inventory = chestAt(20, 64, 4);
        Block sign = signAt(21, 64, 4, BlockFace.EAST);
        inventory.addItem(item(Material.DIAMOND, 6));
        refresh.markDirty(inventory);
        refresh.unloadChunk(world.getUID(), 1, 0);
        refresh.loadChunk(world.getUID(), 1, 0);
        ticks(110);
        assertThat(counter(sign)).isEqualTo("Q 1 : C 6");
        assertThat(enumerations).hasValue(1); // recounted as a chunk, not as a remembered position
    }

    @Test
    void worldUnloadKeepsOtherWorldsDeferredWork() {
        Inventory inventory = chestAt(4, 64, 4);
        Block sign = signAt(5, 64, 4, BlockFace.EAST);
        inventory.addItem(item(Material.DIAMOND, 12));
        refresh.markDirty(inventory);
        refresh.unloadChunk(world.getUID(), 0, 0);
        refresh.unloadWorld(java.util.UUID.randomUUID());
        refresh.loadChunk(world.getUID(), 0, 0);
        ticks(100);
        assertThat(counter(sign)).isEqualTo("Q 1 : C 12");
    }

    @Test
    void repeatedUnloadWithoutALoadKeepsEveryDroppedPosition() {
        Inventory first = chestAt(4, 64, 4);
        Block firstSign = signAt(5, 64, 4, BlockFace.EAST);
        Inventory second = chestAt(8, 64, 4);
        Block secondSign = signAt(9, 64, 4, BlockFace.EAST);
        first.addItem(item(Material.DIAMOND, 3));
        second.addItem(item(Material.DIAMOND, 4));
        refresh.markDirty(first);
        refresh.unloadChunk(world.getUID(), 0, 0);
        refresh.markDirty(second); // a missed load event must not overwrite the earlier set
        refresh.unloadChunk(world.getUID(), 0, 0);
        refresh.loadChunk(world.getUID(), 0, 0);
        ticks(100);
        assertThat(counter(firstSign)).isEqualTo("Q 1 : C 3");
        assertThat(counter(secondSign)).isEqualTo("Q 1 : C 4");
    }
}
