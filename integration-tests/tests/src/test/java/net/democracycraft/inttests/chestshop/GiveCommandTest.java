package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Real player command dispatch; RCON only prepares and independently observes inventories. */
class GiveCommandTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @ParameterizedTest
    @CsvSource({"chestshop,diamond,1,DIAMOND", "cs,REDSTONE_BLOCK 65,65,REDSTONE_BLOCK"})
    void omittedTargetGivesExactQuantityToSender(String root, String arguments, int quantity, String material) {
        try (MockPlayer admin = MockPlayer.connectLocal(Usernames.fresh("Gift"))) {
            Perms.grant(admin.getUsername(), "chestshop.admin");
            Inv.clear(admin.getUsername());
            admin.chatCommand("/" + root + " give " + arguments);
            admin.awaitMessageContaining("Given", TIMEOUT);
            Barrier.settle();
            assertThat(Inv.playerCount(admin.getUsername(), material)).isEqualTo(quantity);
        }
    }

    @Test
    void explicitTargetReceivesItemsAndRevokedAdminCannotGiveAgain() {
        try (MockPlayer admin = MockPlayer.connectLocal(Usernames.fresh("Gift"));
             MockPlayer recipient = MockPlayer.connectLocal(Usernames.fresh("Recv"))) {
            Perms.grant(admin.getUsername(), "chestshop.admin");
            Inv.clear(admin.getUsername());
            Inv.clear(recipient.getUsername());
            RconClient.send("diag warmname " + recipient.getUsername());
            admin.chatCommand("/chestshop give diamond 17 " + recipient.getUsername());
            admin.awaitMessageContaining("Given", TIMEOUT);
            Barrier.settle();
            assertThat(Inv.playerCount(recipient.getUsername(), "DIAMOND")).isEqualTo(17);
            assertThat(Inv.playerCount(admin.getUsername(), "DIAMOND")).isZero();

            RconClient.send("lp user " + admin.getUsername() + " permission set chestshop.admin false");
            Await.until("admin permission revoked", () -> Perms.check(admin.getUsername(), "chestshop.admin"),
                    reply -> reply.contains("has=false"), TIMEOUT);
            admin.chatCommand("/cs give diamond 9 " + recipient.getUsername());
            Barrier.settle();
            assertThat(Inv.playerCount(recipient.getUsername(), "DIAMOND")).isEqualTo(17);
            assertThat(Inv.playerCount(admin.getUsername(), "DIAMOND")).isZero();
        }
    }

    @ParameterizedTest
    @CsvSource({"diamond 0,invalid quantity", "diamond -1,invalid quantity", "no_such_material 4,invalid item id"})
    void invalidGiftLeavesExistingItemsUntouched(String arguments, String reply) {
        try (MockPlayer admin = MockPlayer.connectLocal(Usernames.fresh("Gift"))) {
            Perms.grant(admin.getUsername(), "chestshop.admin");
            Inv.clear(admin.getUsername());
            Inv.give(admin.getUsername(), "DIAMOND", 7);
            admin.chatCommand("/chestshop give " + arguments);
            admin.awaitMessageContaining(reply, TIMEOUT);
            Barrier.settle();
            assertThat(Inv.playerCount(admin.getUsername(), "DIAMOND")).isEqualTo(7);
        }
    }
}
