package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class FirmAttributeAuthorizationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String ORIGINAL = "https://discord.gg/ab";
    private static final String REPLACEMENT = "https://discord.gg/ABCDEFGHIJKLMNOPQRSTUVWXYZ123456";

    @ParameterizedTest
    @ValueSource(strings = {"business", "firm", "company", "db", "democracybusiness"})
    void proprietorRoleAndServerPermissionsGateMetadataIndependently(String root) {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("AtOwn"));
             MockPlayer outsider = MockPlayer.connectLocal(Usernames.fresh("AtOut"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(outsider.getUuid(), TIMEOUT);
            String firm = Usernames.fresh("AtFrm");
            Ops.run(owner.getUsername(), () -> command(owner, "/" + root + " create " + firm));
            int id = BusinessLedger.waitForFirm(firm, TIMEOUT);
            for (MockPlayer player : List.of(owner, outsider)) {
                Perms.grant(player.getUsername(), "business.attribute.hq");
                Perms.grant(player.getUsername(), "business.attribute.discord");
            }
            long checkpoint = Ledger.transactionCheckpoint();
            success(command(owner, "/" + root + " attribute set hq " + id + " original499"));
            success(command(owner, "/" + root + " attribute set discord " + id + " " + ORIGINAL));
            metadata(id, "original499", ORIGINAL);
            denied(command(outsider, "/" + root + " attribute set hq " + firm + " forbidden499"));
            metadata(id, "original499", ORIGINAL);
            denied(command(outsider, "/" + root + " attribute set discord " + firm + " " + REPLACEMENT));
            metadata(id, "original499", ORIGINAL);
            // A server administrator can override firm membership, with just this node.
            Perms.grant(outsider.getUsername(), "business.admin.attribute");
            success(command(outsider, "/" + root + " admin set hq " + id + " override499"));
            success(command(outsider, "/" + root + " admin set discord " + id + " " + REPLACEMENT));
            metadata(id, "override499", REPLACEMENT);
            revoke(outsider, "business.admin.attribute");
            permissionDenied(command(outsider, "/" + root + " admin set hq " + firm + " forbidden499"));
            metadata(id, "override499", REPLACEMENT);
            permissionDenied(command(outsider, "/" + root + " admin set discord " + firm + " " + ORIGINAL));
            metadata(id, "override499", REPLACEMENT);
            revoke(owner, "business.attribute.hq");
            revoke(owner, "business.attribute.discord");
            permissionDenied(command(owner, "/" + root + " attribute set hq " + firm + " forbidden499"));
            metadata(id, "override499", REPLACEMENT);
            permissionDenied(command(owner, "/" + root + " attribute set discord " + firm + " " + ORIGINAL));
            metadata(id, "override499", REPLACEMENT);
            assertThat(BusinessLedger.firmProprietor(id)).hasValue(owner.getUuid());
            assertThat(BusinessLedger.firmDefaultBalance(id).orElseThrow()).isEqualByComparingTo("0");
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
            for (MockPlayer player : List.of(owner, outsider)) {
                assertThat(Ledger.personalBalance(player.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://discord.gg/abc", "https://example.com/abc", "javascript:alert(1)",
            "https://discord.gg/abc<red>bad", "https://discord.gg/abc https://example.com",
            "https://discord.gg/a", "https://discord.gg/ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567"
    })
    void invalidInvitesPreserveExistingMetadataOnBothRoutes(String invalid) {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("UrlOw"));
             MockPlayer admin = MockPlayer.connectLocal(Usernames.fresh("UrlAd"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(admin.getUuid(), TIMEOUT);
            String firm = Usernames.fresh("UrlFm");
            Ops.run(owner.getUsername(), () -> command(owner, "/business create " + firm));
            int id = BusinessLedger.waitForFirm(firm, TIMEOUT);
            Perms.grant(owner.getUsername(), "business.attribute.discord");
            Perms.grant(admin.getUsername(), "business.admin.attribute");
            success(command(owner, "/business attribute set discord " + firm + " " + ORIGINAL));
            long checkpoint = Ledger.transactionCheckpoint();
            assertThat(command(owner, "/business attribute set discord " + firm + " " + invalid))
                    .anyMatch(m -> m.contains("Invalid URL."));
            assertThat(BusinessLedger.firmDiscordUrl(id)).hasValue(ORIGINAL);
            success(command(admin, "/business admin set discord " + firm + " " + REPLACEMENT));
            assertThat(command(admin, "/business admin set discord " + firm + " " + invalid))
                    .anyMatch(m -> m.contains("Invalid URL."));
            assertThat(BusinessLedger.firmDiscordUrl(id)).hasValue(REPLACEMENT);
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.personalBalance(admin.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
        }
    }

    private static void metadata(int id, String hq, String discord) {
        assertThat(BusinessLedger.firmHqRegion(id)).hasValue(hq);
        assertThat(BusinessLedger.firmDiscordUrl(id)).hasValue(discord);
    }
    private static void success(List<String> messages) {
        assertThat(messages).anyMatch(m -> m.contains("Successfully set"));
    }
    private static void denied(List<String> messages) {
        assertThat(messages).anyMatch(m -> m.contains("You do not have permission"));
        assertThat(messages).noneMatch(m -> m.contains("Successfully set"));
    }
    private static void permissionDenied(List<String> messages) {
        assertThat(messages).contains("You don't have permission to do that.");
        assertThat(messages).noneMatch(m -> m.contains("Successfully set"));
    }
    private static void revoke(MockPlayer player, String node) {
        RconClient.send("lp user " + player.getUsername() + " permission set " + node + " false");
        Await.until("revoked " + node, () -> Perms.check(player.getUsername(), node), r -> r.contains("has=false"), TIMEOUT);
    }
    private static List<String> command(MockPlayer player, String command) {
        player.drainMessages(); player.chatCommand(command); Barrier.settle();
        return player.collectedMessages();
    }
}
