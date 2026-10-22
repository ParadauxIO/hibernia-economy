package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import static org.assertj.core.api.Assertions.assertThat;

class FirmRenameLifecycleTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @ValueSource(strings = {"business", "firm", "company", "db", "democracybusiness"})
    void renamePreservesFundedIdentityAndRejectsConflictsAndRevokedAccess(String root) {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("RnOwn"));
             MockPlayer otherOwner = MockPlayer.connectLocal(Usernames.fresh("RnOth"));
             MockPlayer admin = MockPlayer.connectLocal(Usernames.fresh("RnAdm"))) {
            for (MockPlayer p : List.of(owner, otherOwner, admin)) Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            String original = Usernames.fresh("RnOld"), renamed = Usernames.fresh("RnNew"), occupied = Usernames.fresh("RnDup");
            setup(owner, "/" + root + " create " + original);
            int id = BusinessLedger.waitForFirm(original, TIMEOUT);
            int primary = BusinessLedger.firmDefaultAccountId(id).orElseThrow();
            setup(owner, "/" + root + " account create " + original + " Reserve" + original);
            int reserve = BusinessLedger.firmAccountIdAt(id, 1);
            setup(owner, "/paya account " + primary + " 23.45 RenamePrimary");
            setup(owner, "/paya account " + reserve + " 11 RenameReserve");
            setup(owner, "/" + root + " attribute set hq " + original + " rename499");
            setup(owner, "/" + root + " attribute set discord " + original + " https://discord.gg/rename499");
            setup(otherOwner, "/" + root + " create " + occupied);
            int otherId = BusinessLedger.waitForFirm(occupied, TIMEOUT);
            long checkpoint = Ledger.transactionCheckpoint();
            revoke(admin);
            assertThat(command(admin, "/" + root + " admin rename " + original + " " + renamed))
                    .contains("You don't have permission to do that.");
            assertThat(BusinessLedger.firmDisplayName(id)).hasValue(original);
            Perms.grant(admin.getUsername(), "business.admin.rename");
            renamed(command(admin, "/" + root + " admin rename " + id + " " + renamed), original, renamed);
            assertThat(BusinessLedger.firmId(original)).isEmpty();
            assertThat(BusinessLedger.firmId(renamed)).hasValue(id);
            assertThat(BusinessLedger.firmDisplayName(id)).hasValue(renamed);
            // A no-op and case-only change must not be mistaken for another firm's collision.
            renamed(command(admin, "/" + root + " admin rename " + renamed + " " + renamed), renamed, renamed);
            String upper = renamed.toUpperCase(Locale.ROOT);
            renamed(command(admin, "/" + root + " admin rename " + id + " " + upper), renamed, upper);
            assertThat(BusinessLedger.firmDisplayName(id)).hasValue(upper);
            var collision = command(admin, "/" + root + " admin rename " + id + " " + occupied.toUpperCase(Locale.ROOT));
            assertThat(collision).anyMatch(m -> m.contains("already exists"));
            assertThat(BusinessLedger.firmDisplayName(id)).hasValue(upper);
            assertThat(BusinessLedger.firmDisplayName(otherId)).hasValue(occupied);
            assertThat(command(admin, "/" + root + " admin rename " + id + " 9invalid"))
                    .anyMatch(m -> m.contains("Firm names cannot start with a number."));
            assertThat(BusinessLedger.firmDisplayName(id)).hasValue(upper);
            revoke(admin);
            assertThat(command(admin, "/" + root + " admin rename " + id + " " + original))
                    .contains("You don't have permission to do that.");
            assertThat(BusinessLedger.firmDisplayName(id)).hasValue(upper);
            assertThat(BusinessLedger.firmProprietor(id)).hasValue(owner.getUuid());
            assertThat(BusinessLedger.firmProprietor(otherId)).hasValue(otherOwner.getUuid());
            assertThat(BusinessLedger.firmDefaultAccountId(id)).hasValue(primary);
            assertThat(BusinessLedger.firmAccountIdAt(id, 1)).isEqualTo(reserve);
            assertThat(BusinessLedger.activeFirmAccountCount(id)).isEqualTo(2);
            assertThat(BusinessLedger.firmHqRegion(id)).hasValue("rename499");
            assertThat(BusinessLedger.firmDiscordUrl(id)).hasValue("https://discord.gg/rename499");
            assertThat(Ledger.accountBalanceById(primary).orElseThrow()).isEqualByComparingTo("23.45");
            assertThat(Ledger.accountBalanceById(reserve).orElseThrow()).isEqualByComparingTo("11");
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("9965.55");
            assertThat(Ledger.personalBalance(otherOwner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.personalBalance(admin.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
        }
    }
    private static void renamed(List<String> messages, String before, String after) {
        assertThat(messages).anyMatch(m -> m.contains("Renamed " + before + " to " + after + "."));
    }
    private static void revoke(MockPlayer player) {
        RconClient.send("lp user " + player.getUsername() + " permission set business.admin.rename false");
        Await.until("rename permission revoked", () -> Perms.check(player.getUsername(), "business.admin.rename"),
                r -> r.contains("has=false"), TIMEOUT);
    }
    private static void setup(MockPlayer player, String command) {
        Ops.run(player.getUsername(), () -> command(player, command));
    }
    private static List<String> command(MockPlayer player, String command) {
        player.drainMessages(); player.chatCommand(command); Barrier.settle();
        return player.collectedMessages();
    }
}
