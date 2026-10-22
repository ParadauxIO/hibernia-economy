package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.Test;
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
            player.chatCommand("/firm create " + firm);
            player.awaitMessageContaining("Successfully created", TIMEOUT);
            int id = BusinessLedger.waitForFirm(firm, TIMEOUT);
            NbtMap chooser = Dialogs.await(player, () -> player.chatCommand("/firm menu"), d -> Dialogs.title(d).equals("Firm menu"));
            NbtMap overview = Dialogs.await(player, () -> Dialogs.click(player, chooser, "Open firm", Map.of("firm", "" + id)), d -> Dialogs.title(d).equals(firm));
            assertThat(Dialogs.buttons(overview).stream().map(b -> Dialogs.text(b.get("label"))))
                    .contains("Money", "Staff", "Accounts (★ default)");
            NbtMap accounts = Dialogs.await(player, () -> Dialogs.click(player, overview, "Accounts (★ default)", Map.of()), d -> Dialogs.title(d).contains("Accounts"));
            Dialogs.click(player, accounts, "Submit action", Map.of("operation", "CREATE_ACCOUNT", "target", "Reserve" + id));
            player.awaitMessageContaining("created account", TIMEOUT);
            Await.untilEquals("new account", () -> BusinessLedger.firmAccountCount(id), 2, TIMEOUT);

            NbtMap funded = Dialogs.await(player, () -> player.chatCommand("/firm menu " + id), d -> Dialogs.title(d).equals(firm));
            NbtMap deposit = Dialogs.await(player, () -> Dialogs.click(player, funded, "Money", Map.of()), d -> Dialogs.title(d).equals("Money"));
            var personal = Ledger.personalBalance(player.getUuid()).orElseThrow();
            Dialogs.click(player, deposit, "Submit action", Map.of("operation", "DEPOSIT", "target", "", "amount", "1.25"));
            player.awaitMessageContaining("Deposited", TIMEOUT);
            BusinessLedger.waitForFirmDefaultBalance(id, new java.math.BigDecimal("1.25"), TIMEOUT);
            assertThat(Ledger.personalBalance(player.getUuid()).orElseThrow()).isEqualByComparingTo(personal.subtract(new java.math.BigDecimal("1.25")));

            NbtMap next = Dialogs.await(player, () -> player.chatCommand("/firm menu " + id), d -> Dialogs.title(d).equals(firm));
            NbtMap money = Dialogs.await(player, () -> Dialogs.click(player, next, "Money", Map.of()), d -> Dialogs.title(d).equals("Money"));
            RconClient.send("lp user " + owner + " permission set business.finance false");
            Await.until("finance denied", () -> Perms.check(owner, "business.finance"), r -> r.contains("has=false"), TIMEOUT);
            var before = Ledger.personalBalance(player.getUuid()).orElseThrow();
            Dialogs.click(player, money, "Submit action", Map.of("operation", "DEPOSIT", "target", "", "amount", "1.00"));
            player.awaitMessageContaining("permission", TIMEOUT);
            Barrier.settle();
            assertThat(Ledger.personalBalance(player.getUuid()).orElseThrow()).isEqualByComparingTo(before);
            assertThat(BusinessLedger.firmAccountCount(id)).isEqualTo(2);
        }
    }
    @Test
    void staffFormsOfferAndFireAndUnprivilegedMoneyActionIsDenied() {
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
            NbtMap main = Dialogs.await(player, () -> player.chatCommand("/firm menu " + id), d -> Dialogs.title(d).equals(firm));
            NbtMap staff = Dialogs.await(player, () -> Dialogs.click(player, main, "Staff", Map.of()), d -> Dialogs.title(d).equals("Staff"));
            Dialogs.click(player, staff, "Submit action", Map.of("operation", "HIRE", "target", recruit));
            player.awaitMessageContaining("job offer has sent", TIMEOUT);
            employee.chatCommand("/firm offer accept " + id);
            employee.awaitMessageContaining("accepted a job offer", TIMEOUT);
            Await.until("hire persisted", () -> BusinessLedger.isCurrentEmployee(id, employee.getUuid()), Boolean.TRUE::equals, TIMEOUT);

            NbtMap employeeMain = Dialogs.await(employee, () -> employee.chatCommand("/firm menu " + id), d -> Dialogs.title(d).equals(firm));
            NbtMap employeeMoney = Dialogs.await(employee, () -> Dialogs.click(employee, employeeMain, "Money", Map.of()), d -> Dialogs.title(d).equals("Money"));
            var before = Ledger.personalBalance(employee.getUuid()).orElseThrow();
            Dialogs.click(employee, employeeMoney, "Submit action", Map.of("operation", "WITHDRAW", "target", "", "amount", "1"));
            employee.awaitMessageContaining("permission", TIMEOUT);
            Barrier.settle();
            assertThat(Ledger.personalBalance(employee.getUuid()).orElseThrow()).isEqualByComparingTo(before);

            NbtMap refreshed = Dialogs.await(player, () -> player.chatCommand("/firm menu " + id), d -> Dialogs.title(d).equals(firm));
            NbtMap firing = Dialogs.await(player, () -> Dialogs.click(player, refreshed, "Staff", Map.of()), d -> Dialogs.title(d).equals("Staff"));
            Dialogs.click(player, firing, "Submit action", Map.of("operation", "FIRE", "target", recruit));
            player.awaitMessageContaining("has been fired", TIMEOUT);
            Await.until("fire persisted", () -> BusinessLedger.isCurrentEmployee(id, employee.getUuid()), Boolean.FALSE::equals, TIMEOUT);
        }
    }

}
