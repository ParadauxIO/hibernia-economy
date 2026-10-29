package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.Test;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import java.time.Duration;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/** Exercises real Paper-issued dialog callbacks rather than invoking handlers directly. */
class FirmMenuTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void choosesFirmCreatesAccountAndRejectsStaleServerPermission() {
        String owner = Usernames.fresh("Menu");
        String firm = "Menu" + Long.toHexString(System.nanoTime());
        try (MockPlayer player = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(player.getUuid(), TIMEOUT);
            var empty = Menus.open(player, () -> player.chatCommand("/firm menu"));
            assertThat(Menus.slot(player, 0).toString()).contains("You do not own");
            player.closeContainer(empty.getContainerId());
            Barrier.sync(player);
            player.chatCommand("/firm create " + firm);
            player.awaitMessageContaining("Successfully created", TIMEOUT);
            int id = BusinessLedger.waitForFirm(firm, TIMEOUT);
            var chooser = Menus.open(player, () -> player.chatCommand("/firm menu"));
            assertThat(Menus.state(player).get("title").getAsString()).isEqualTo("Firm menu");
            var overview = Menus.click(player, chooser, 0, ClickItemAction.LEFT_CLICK);
            assertThat(Menus.state(player).get("title").getAsString()).isEqualTo(firm);
            assertThat(Menus.slot(player, 11).toString()).contains("Money");
            var accountList = Menus.click(player, overview, 15, ClickItemAction.LEFT_CLICK);
            assertThat(Menus.slot(player, 0).toString()).contains("★");
            NbtMap firstForm = Dialogs.await(player, () -> click(player, accountList, 49), d -> Dialogs.title(d).contains("Accounts"));
            var returned = Menus.open(player, () -> Dialogs.click(player, firstForm, "Back", Map.of()));
            assertThat(Menus.state(player).get("title").getAsString()).isEqualTo(firm);
            var returnedList = Menus.click(player, returned, 15, ClickItemAction.LEFT_CLICK);
            NbtMap accounts = Dialogs.await(player, () -> click(player, returnedList, 49), d -> Dialogs.title(d).contains("Accounts"));
            Dialogs.click(player, accounts, "Submit", Map.of("operation", "CREATE_ACCOUNT", "target", "Reserve" + id));
            player.awaitMessageContaining("created account", TIMEOUT);
            Await.untilEquals("new account", () -> BusinessLedger.firmAccountCount(id), 2, TIMEOUT);

            var funded = Menus.open(player, () -> player.chatCommand("/firm menu " + id));
            NbtMap deposit = Dialogs.await(player, () -> click(player, funded, 11), d -> Dialogs.title(d).equals("Money"));
            var personal = Ledger.personalBalance(player.getUuid()).orElseThrow();
            Dialogs.click(player, deposit, "Submit", Map.of("operation", "DEPOSIT", "target", "", "amount", "1.25"));
            player.awaitMessageContaining("Paid", TIMEOUT);
            BusinessLedger.waitForFirmDefaultBalance(id, new java.math.BigDecimal("1.25"), TIMEOUT);
            assertThat(Ledger.personalBalance(player.getUuid()).orElseThrow()).isEqualByComparingTo(personal.subtract(new java.math.BigDecimal("1.25")));

            var next = Menus.open(player, () -> player.chatCommand("/firm menu " + id));
            NbtMap money = Dialogs.await(player, () -> click(player, next, 11), d -> Dialogs.title(d).equals("Money"));
            RconClient.send("lp user " + owner + " permission set business.finance false");
            Await.until("finance denied", () -> Perms.check(owner, "business.finance"), r -> r.contains("has=false"), TIMEOUT);
            var before = Ledger.personalBalance(player.getUuid()).orElseThrow();
            Dialogs.click(player, money, "Submit", Map.of("operation", "DEPOSIT", "target", "", "amount", "1.00"));
            player.awaitMessageContaining("permission", TIMEOUT);
            Barrier.settle();
            assertThat(Ledger.personalBalance(player.getUuid()).orElseThrow()).isEqualByComparingTo(before);
            assertThat(BusinessLedger.firmAccountCount(id)).isEqualTo(2);
        }
    }
    @Test
    void staffFormsOfferAndFireAndRejectRoleRevokedAfterOpeningMoneyForm() {
        String owner = Usernames.fresh("MOwn");
        String recruit = Usernames.fresh("MHire");
        String firm = "StaffMenu" + Long.toHexString(System.nanoTime());
        try (MockPlayer player = MockPlayer.connectLocal(owner);
             MockPlayer employee = MockPlayer.connectLocal(recruit)) {
            Ledger.waitForPersonalBalance(player.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(employee.getUuid(), TIMEOUT);
            player.chatCommand("/firm create " + firm);
            player.awaitMessageContaining("Successfully created", TIMEOUT);
            int id = BusinessLedger.waitForFirm(firm, TIMEOUT);
            var main = Menus.open(player, () -> player.chatCommand("/firm menu " + id));
            var roster = Menus.click(player, main, 13, ClickItemAction.LEFT_CLICK);
            NbtMap staff = Dialogs.await(player, () -> click(player, roster, 49), d -> Dialogs.title(d).equals("Staff"));
            Dialogs.click(player, staff, "Submit", Map.of("operation", "HIRE", "target", recruit));
            player.awaitMessageContaining("job offer has sent", TIMEOUT);
            employee.chatCommand("/firm offer accept " + id);
            employee.awaitMessageContaining("accepted a job offer", TIMEOUT);
            Await.until("hire persisted", () -> BusinessLedger.isCurrentEmployee(id, employee.getUuid()), Boolean.TRUE::equals, TIMEOUT);

            for (String role : java.util.List.of("Supervisor", "Manager")) {
                player.chatCommand("/firm staff promote " + id + " " + recruit);
                player.awaitMessageContaining("has been promoted to " + role, TIMEOUT);
                BusinessLedger.waitForEmployeeRole(id, employee.getUuid(), role, TIMEOUT);
            }
            var employeeMain = Menus.open(employee, () -> employee.chatCommand("/firm menu " + id));
            NbtMap employeeMoney = Dialogs.await(employee, () -> click(employee, employeeMain, 11), d -> Dialogs.title(d).equals("Money"));
            player.chatCommand("/firm staff demote " + id + " " + recruit);
            player.awaitMessageContaining("has been demoted to Supervisor", TIMEOUT);
            BusinessLedger.waitForEmployeeRole(id, employee.getUuid(), "Supervisor", TIMEOUT);
            var before = Ledger.personalBalance(employee.getUuid()).orElseThrow();
            Dialogs.click(employee, employeeMoney, "Submit", Map.of("operation", "WITHDRAW", "target", "", "amount", "1"));
            employee.awaitMessageContaining("permission", TIMEOUT);
            Barrier.settle();
            assertThat(Ledger.personalBalance(employee.getUuid()).orElseThrow()).isEqualByComparingTo(before);

            var refreshed = Menus.open(player, () -> player.chatCommand("/firm menu " + id));
            var newRoster = Menus.click(player, refreshed, 13, ClickItemAction.LEFT_CLICK);
            NbtMap firing = Dialogs.await(player, () -> click(player, newRoster, 49), d -> Dialogs.title(d).equals("Staff"));
            Dialogs.click(player, firing, "Submit", Map.of("operation", "FIRE", "target", recruit));
            player.awaitMessageContaining("has been fired", TIMEOUT);
            Await.until("fire persisted", () -> BusinessLedger.isCurrentEmployee(id, employee.getUuid()), Boolean.FALSE::equals, TIMEOUT);
        }
    }

    @Test
    void curatorDashboardRejectsItemEscapeAndStaleWindowPackets() {
        try (MockPlayer player = MockPlayer.connectLocal(Usernames.fresh("Safe"))) {
            Ledger.waitForPersonalBalance(player.getUuid(), TIMEOUT);
            String firm = "SafeMenu" + Long.toHexString(System.nanoTime());
            player.chatCommand("/firm create " + firm);
            player.awaitMessageContaining("Successfully created", TIMEOUT);
            int id = BusinessLedger.waitForFirm(firm, TIMEOUT);
            Inv.clear(player.getUsername());
            Inv.give(player.getUsername(), "EMERALD", 7);
            var screen = Menus.open(player, () -> player.chatCommand("/firm menu " + id));
            record Attempt(ContainerActionType type, ContainerAction action) {}
            var attempts = java.util.List.of(
                new Attempt(ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK),
                new Attempt(ContainerActionType.CLICK_ITEM, ClickItemAction.RIGHT_CLICK),
                new Attempt(ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.LEFT_CLICK),
                new Attempt(ContainerActionType.MOVE_TO_HOTBAR_SLOT, MoveToHotbarAction.SLOT_1),
                new Attempt(ContainerActionType.MOVE_TO_HOTBAR_SLOT, MoveToHotbarAction.OFF_HAND),
                new Attempt(ContainerActionType.DROP_ITEM, DropItemAction.DROP_SELECTED_STACK));
            for (var attempt : attempts) {
                var before = Menus.state(player);
                rawClick(player, screen, 4, attempt.type(), attempt.action());
                Barrier.sync(player);
                assertProtected(player, before);
            }
            var before = Menus.state(player);
            rawClick(player, screen, 54, ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.LEFT_CLICK);
            Barrier.sync(player);
            assertProtected(player, before);

            RconClient.send("diag menucursor " + player.getUsername() + " GOLD_INGOT 8");
            before = Menus.state(player);
            rawClick(player, screen, -999, ContainerActionType.SPREAD_ITEM, SpreadItemAction.LEFT_MOUSE_BEGIN_DRAG);
            rawClick(player, screen, 4, ContainerActionType.SPREAD_ITEM, SpreadItemAction.LEFT_MOUSE_ADD_SLOT);
            rawClick(player, screen, 55, ContainerActionType.SPREAD_ITEM, SpreadItemAction.LEFT_MOUSE_ADD_SLOT);
            rawClick(player, screen, -999, ContainerActionType.SPREAD_ITEM, SpreadItemAction.LEFT_MOUSE_END_DRAG);
            Barrier.sync(player);
            assertProtected(player, before);
            RconClient.send("diag menucursor " + player.getUsername() + " AIR 0");
            RconClient.send("gamemode creative " + player.getUsername());
            before = Menus.state(player);
            rawClick(player, screen, 4, ContainerActionType.CREATIVE_GRAB_MAX_STACK, CreativeGrabAction.GRAB);
            Barrier.sync(player);
            assertProtected(player, before);
            RconClient.send("gamemode survival " + player.getUsername());

            var oldContent = player.containerContent(screen.getContainerId()).orElseThrow();
            var replacement = Menus.open(player, () -> RconClient.send("diag menuplain " + player.getUsername()));
            before = Menus.state(player);
            player.sendPacket(new ServerboundContainerClickPacket(screen.getContainerId(), oldContent.getStateId(), 4,
                    ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.LEFT_CLICK, null, Map.of()));
            Barrier.sync(player);
            assertProtected(player, before);
            // Positive control: the same client can take an item from an ordinary chest.
            rawClick(player, replacement, 0, ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.LEFT_CLICK);
            Barrier.sync(player);
            assertThat(Inv.playerCount(player.getUsername(), "DIAMOND")).isEqualTo(7);
            assertThat(Inv.playerCount(player.getUsername(), "EMERALD")).isEqualTo(7);
            assertThat(Inv.playerCount(player.getUsername(), "GOLD_INGOT")).isZero();
        }
    }

    private static void assertProtected(MockPlayer player, com.google.gson.JsonObject before) {
        var after = Menus.state(player);
        for (String key : java.util.List.of("inventory", "cursor", "top", "drops"))
            assertThat(after.get(key)).as("unchanged " + key).isEqualTo(before.get(key));
    }

    private static void click(MockPlayer player, ClientboundOpenScreenPacket screen, int slot) {
        rawClick(player, screen, slot, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK);
    }
    private static void rawClick(MockPlayer player, ClientboundOpenScreenPacket screen, int slot,
                                 ContainerActionType type, ContainerAction action) {
        var content = player.containerContent(screen.getContainerId()).orElseThrow();
        player.sendPacket(new ServerboundContainerClickPacket(screen.getContainerId(), content.getStateId(), slot,
                type, action, null, Map.of()));
    }

}
