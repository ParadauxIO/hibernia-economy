package net.democracycraft.inttests.chestshop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import net.kyori.adventure.key.Key;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.*;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomClickActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundShowDialogGamePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.*;
import org.junit.jupiter.api.*;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Paper + protocol packets. Diag observes final state; it never synthesises inventory clicks. */
class FindMenuE2ETest {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private MockPlayer player;

    @BeforeEach
    void setup() throws Exception {
        player = MockPlayer.connectLocal(Usernames.fresh("Menu"));
        Perms.grant(player.getUsername(), "chestshop.find");
        RconClient.send("gamemode survival " + player.getUsername());
        Inv.clear(player.getUsername());
        setting("left", "");
        setting("right", "");
        // Isolated registry fixtures: the real /find service reads all 46 rows through Treasury.
        try (var connection = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM chestshop_shop WHERE world='world' AND sign_y=310 AND sign_z=9876");
            for (int i = 0; i < 46; i++) {
                statement.executeUpdate("INSERT INTO chestshop_shop (world,sign_x,sign_y,sign_z,is_admin_shop,material,item_key,item_name,"
                        + "item_custom,buy_price,batch_qty,active,visible,hologram) VALUES ('world'," + i
                        + ",310,9876,1,'SPONGE','SPONGE','Sponge',0,5.00,1,1,1,1)");
            }
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        if (player != null) player.close();
        setting("left", "compass transit <x> <y> <z>");
        setting("right", "compass <x> <y> <z>");
        try (var connection = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM chestshop_shop WHERE world='world' AND sign_y=310 AND sign_z=9876");
        }
    }

    @Test
    void searchPagesAndPreviewUseCompactTitlesAndReturnToTheSamePage() {
        var screen = search();
        assertThat(screen.getType()).isEqualTo(ContainerType.GENERIC_9X6);
        assertThat(state().get("title").getAsString()).isEqualTo("Shop results");
        assertThat(slot(44).get("material").getAsString()).isEqualTo("CHEST");
        assertThat(slot(49).get("material").getAsString()).isEqualTo("SPONGE");
        assertThat(slot(49).get("lore").getAsString()).contains("46 shops", "Page 1/2");
        screen = navigate(screen, 50, ClickItemAction.LEFT_CLICK);
        assertThat(slot(1).get("material").getAsString()).isEqualTo("AIR");
        assertThat(slot(49).get("lore").getAsString()).contains("Page 2/2");
        String page = state().get("top").getAsString();
        var preview = navigate(screen, 0, ClickItemAction.RIGHT_CLICK);
        assertThat(preview.getType()).isEqualTo(ContainerType.GENERIC_9X3);
        assertThat(state().get("title").getAsString()).isEqualTo("Item preview");
        assertThat(slot(13).get("material").getAsString()).isEqualTo("SPONGE");
        int previewId = preview.getContainerId();
        screen = player.awaitClientboundAfter(() -> player.sendPacket(new ServerboundContainerClosePacket(previewId)),
                ClientboundOpenScreenPacket.class, p -> true, TIMEOUT);
        Barrier.sync(player);
        assertThat(state().get("top").getAsString()).isEqualTo(page);
        var backScreen = screen;
        preview = navigate(backScreen, 0, ClickItemAction.RIGHT_CLICK);
        screen = navigate(preview, 18, ClickItemAction.LEFT_CLICK);
        assertThat(state().get("top").getAsString()).isEqualTo(page);
        player.sendPacket(new ServerboundContainerClosePacket(screen.getContainerId()));
        Barrier.sync(player);
        assertThat(Inv.playerCount(player.getUsername(), "SPONGE")).isZero();
    }

    @Test
    void resultAndPreviewRejectRealTransferPacketsIncludingCreativeCloningAndDrag() {
        Inv.give(player.getUsername(), "EMERALD", 7);
        var results = navigate(search(), 50, ClickItemAction.LEFT_CLICK);
        assertProtected(results, 49); // queried item is protected display, never an action
        var preview = navigate(results, 0, ClickItemAction.RIGHT_CLICK);
        assertProtected(preview, 13);
        player.sendPacket(new ServerboundContainerClosePacket(preview.getContainerId()));
        Barrier.sync(player);
        Barrier.settle(player);
        assertThat(Inv.playerCount(player.getUsername(), "SPONGE")).isZero();
        assertThat(Inv.playerCount(player.getUsername(), "EMERALD")).isEqualTo(7);
    }

    @Test
    void staleWindowPacketsCannotStealItemsFromAReplacementInventory() {
        var results = search();
        var oldContent = content(results);
        var other = player.awaitClientboundAfter(() -> RconClient.send("diag menuplain " + player.getUsername()),
                ClientboundOpenScreenPacket.class, p -> true, TIMEOUT);
        Barrier.sync(player);
        var before = state();
        player.sendPacket(new ServerboundContainerClickPacket(results.getContainerId(), oldContent.getStateId(), 0,
                ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.LEFT_CLICK, null, Map.of()));
        Barrier.sync(player);
        assertThat(state().get("inventory")).isEqualTo(before.get("inventory"));
        assertThat(Inv.playerCount(player.getUsername(), "SPONGE")).isZero();
        // Positive control: this client really can transfer an item from an ordinary chest.
        var beforeControl = state();
        click(other, 0, ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.LEFT_CLICK);
        assertThat(state().get("clicks").getAsLong()).isGreaterThan(beforeControl.get("clicks").getAsLong());
        assertThat(Inv.playerCount(player.getUsername(), "DIAMOND")).isEqualTo(7);
    }

    @Test
    void shiftClicksRunConfiguredNavigationAsThePlayerWithoutTakingAnIcon() {
        Ops.run(player.getUsername(), () -> {
            setting("left", "minecraft:give @s emerald 2");
            setting("right", "minecraft:give @s iron_ingot 3");
            var left = search();
            rawClick(left, 0, ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.LEFT_CLICK);
            Await.until("left command executed as player",
                    () -> Inv.playerCount(player.getUsername(), "EMERALD"), count -> count == 2, TIMEOUT);
            Barrier.sync(player);
            assertThat(state().get("size").getAsInt()).isNotEqualTo(54);
            var right = search();
            rawClick(right, 0, ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.RIGHT_CLICK);
            Await.until("right command executed as player",
                    () -> Inv.playerCount(player.getUsername(), "IRON_INGOT"), count -> count == 3, TIMEOUT);
            Barrier.sync(player);
            assertThat(state().get("size").getAsInt()).isNotEqualTo(54);
            assertThat(Inv.playerCount(player.getUsername(), "SPONGE")).isZero();
            assertThat(Inv.playerCount(player.getUsername(), "CHEST")).isZero();
        });
    }

    private void assertProtected(ClientboundOpenScreenPacket screen, int target) {
        record Attempt(ContainerActionType type, ContainerAction action) {}
        var attempts = List.of(
                new Attempt(ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK),
                new Attempt(ContainerActionType.CLICK_ITEM, ClickItemAction.RIGHT_CLICK),
                new Attempt(ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.LEFT_CLICK),
                new Attempt(ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.RIGHT_CLICK),
                new Attempt(ContainerActionType.MOVE_TO_HOTBAR_SLOT, MoveToHotbarAction.SLOT_1),
                new Attempt(ContainerActionType.MOVE_TO_HOTBAR_SLOT, MoveToHotbarAction.OFF_HAND),
                new Attempt(ContainerActionType.DROP_ITEM, DropItemAction.DROP_FROM_SELECTED),
                new Attempt(ContainerActionType.DROP_ITEM, DropItemAction.DROP_SELECTED_STACK));
        for (var attempt : attempts) {
            var before = state();
            click(screen, target, attempt.type(), attempt.action());
            assertDeniedAndUnchanged(before, false);
        }
        // A malicious client may claim it already moved a stack, with a stale revision.
        var forgedBefore = state();
        var authoritative = content(screen);
        var fake = new org.geysermc.mcprotocollib.protocol.data.game.item.HashedStack(
                authoritative.getItems()[target].getId(), 64, Map.of(), java.util.Set.of());
        var predictions = new java.util.HashMap<Integer, org.geysermc.mcprotocollib.protocol.data.game.item.HashedStack>();
        predictions.put(target, null);
        predictions.put(state().get("size").getAsInt() + 27, fake);
        player.sendPacket(new ServerboundContainerClickPacket(screen.getContainerId(),
                (authoritative.getStateId() + 32767) & 32767, target,
                ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK, fake, predictions));
        Barrier.sync(player);
        assertDeniedAndUnchanged(forgedBefore, false);

        // Shift transfers from the player's own inventory are blocked too.
        int hotbar = state().get("size").getAsInt() + 27;
        var before = state();
        click(screen, hotbar, ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.LEFT_CLICK);
        assertDeniedAndUnchanged(before, false);

        // Double-click collection needs a nonempty matching cursor to exercise the real path.
        assertThat(RconClient.send("diag menucursor " + player.getUsername() + " SPONGE 1")).contains("updated");
        before = state();
        click(screen, target, ContainerActionType.FILL_STACK, FillStackAction.FILL);
        assertDeniedAndUnchanged(before, false);
        assertThat(RconClient.send("diag menucursor " + player.getUsername() + " SPONGE 8")).contains("updated");
        before = state();
        click(screen, -999, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK);
        assertDeniedAndUnchanged(before, false);
        for (boolean right : List.of(false, true)) {
            before = state();
            rawClick(screen, -999, ContainerActionType.SPREAD_ITEM,
                    right ? SpreadItemAction.RIGHT_MOUSE_BEGIN_DRAG : SpreadItemAction.LEFT_MOUSE_BEGIN_DRAG);
            rawClick(screen, state().get("size").getAsInt() == 54 ? 20 : 13, ContainerActionType.SPREAD_ITEM,
                    right ? SpreadItemAction.RIGHT_MOUSE_ADD_SLOT : SpreadItemAction.LEFT_MOUSE_ADD_SLOT);
            rawClick(screen, hotbar + 1, ContainerActionType.SPREAD_ITEM,
                    right ? SpreadItemAction.RIGHT_MOUSE_ADD_SLOT : SpreadItemAction.LEFT_MOUSE_ADD_SLOT);
            rawClick(screen, -999, ContainerActionType.SPREAD_ITEM,
                    right ? SpreadItemAction.RIGHT_MOUSE_END_DRAG : SpreadItemAction.LEFT_MOUSE_END_DRAG);
            Barrier.sync(player);
            assertDeniedAndUnchanged(before, true);
        }
        assertThat(RconClient.send("diag menucursor " + player.getUsername() + " AIR 0")).contains("updated");
        RconClient.send("gamemode creative " + player.getUsername());
        before = state();
        click(screen, target, ContainerActionType.CREATIVE_GRAB_MAX_STACK, CreativeGrabAction.GRAB);
        assertDeniedAndUnchanged(before, false);
        RconClient.send("gamemode survival " + player.getUsername());
    }

    private void assertDeniedAndUnchanged(JsonObject before, boolean drag) {
        var after = state();
        String event = drag ? "drags" : "clicks";
        assertThat(after.get(event).getAsLong()).as("packet reached Bukkit: " + event)
                .isEqualTo(before.get(event).getAsLong() + 1);
        assertThat(after.get("denied").getAsLong()).isEqualTo(before.get("denied").getAsLong() + 1);
        for (String key : List.of("inventory", "cursor", "top", "drops")) {
            assertThat(after.get(key)).as("unchanged " + key).isEqualTo(before.get(key));
        }
    }

    private ClientboundOpenScreenPacket search() {
        var dialog = player.awaitClientboundAfter(() -> player.chatCommand("find sponge"),
                ClientboundShowDialogGamePacket.class, p -> true, TIMEOUT).getDialog().custom();
        var actions = dialog.getList("actions", NbtType.COMPOUND);
        assertThat(actions).as("search dialog actions: %s", dialog).hasSize(6);
        var action = actions.getLast().getCompound("action");
        // Paper publishes newly created dialog callbacks from its queue on a later tick.
        // An immediate synthetic response can beat registration; wait on observed server ticks.
        int renderedTick = state().get("tick").getAsInt();
        Await.until("dialog callbacks registered", () -> state().get("tick").getAsInt(),
                tick -> tick > renderedTick + 1, TIMEOUT);
        var payloadBuilder = NbtMap.builder();
        payloadBuilder.putAll(action.getCompound("additions"));
        var payload = payloadBuilder.putBoolean("fuzzy", false).build();
        var screen = player.awaitClientboundAfter(() -> player.sendPacket(
                        new ServerboundCustomClickActionPacket(Key.key(action.getString("id")), payload)),
                ClientboundOpenScreenPacket.class, p -> true, TIMEOUT);
        content(screen);
        Barrier.sync(player);
        return screen;
    }

    private ClientboundOpenScreenPacket navigate(ClientboundOpenScreenPacket current, int slot, ClickItemAction action) {
        var next = player.awaitClientboundAfter(() -> rawClick(current, slot, ContainerActionType.CLICK_ITEM, action),
                ClientboundOpenScreenPacket.class, p -> true, TIMEOUT);
        content(next);
        Barrier.sync(player);
        return next;
    }

    private ClientboundContainerSetContentPacket content(ClientboundOpenScreenPacket screen) {
        return Await.until("container contents", () -> player.containerContent(screen.getContainerId()).orElse(null),
                p -> p != null, TIMEOUT);
    }

    private void rawClick(ClientboundOpenScreenPacket screen, int slot, ContainerActionType type, ContainerAction action) {
        player.sendPacket(new ServerboundContainerClickPacket(screen.getContainerId(), content(screen).getStateId(),
                slot, type, action, null, Map.of()));
    }

    private void click(ClientboundOpenScreenPacket screen, int slot, ContainerActionType type, ContainerAction action) {
        rawClick(screen, slot, type, action);
        Barrier.sync(player);
    }

    private JsonObject state() { return json("menustate " + player.getUsername()); }
    private JsonObject slot(int slot) { return json("menuslot " + player.getUsername() + " " + slot); }
    private JsonObject json(String command) {
        String reply = RconClient.send("diag " + command).trim();
        assertThat(reply).as(command).startsWith("{");
        return JsonParser.parseString(reply).getAsJsonObject();
    }
    private void setting(String side, String command) {
        assertThat(RconClient.send("diag menusetting shop-icon-" + side + "-click-command " + command)).contains("updated");
    }
}
