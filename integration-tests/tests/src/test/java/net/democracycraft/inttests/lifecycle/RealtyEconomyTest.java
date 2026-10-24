package net.democracycraft.inttests.lifecycle;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.Duration;
import java.util.Locale;
import static org.assertj.core.api.Assertions.assertThat;

/** Supplied real Realty: actual commands, Treasury settlement and WorldGuard ownership. */
@EnabledIfEnvironmentVariable(named = "IT_REALTY_PROFILE", matches = "true")
class RealtyEconomyTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Test
    void registeredVaultProviderSettlesAndRejectsWithoutPartialPlayerPostings() throws Exception {
        try (var player = MockPlayer.connectLocal(Usernames.fresh("Vlt"))) {
            Ledger.waitForPersonalBalance(player.getUuid(), TIMEOUT);
            int account = Ledger.personalAccountId(player.getUuid()).orElseThrow();
            int initialPostings = Ledger.postingsCountForAccount(account);
            String command = "diag vault withdraw " + player.getUuid() + " ";
            assertThat(RconClient.send(command + "10001")).contains("provider=Treasury", "success=false");
            assertThat(Ledger.personalBalance(player.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.postingsCountForAccount(account)).isEqualTo(initialPostings);
            try (var fault = LedgerFaults.rejectDebits(account)) {
                assertThat(RconClient.send(command + "37")).contains("provider=Treasury", "success=false");
                assertThat(Ledger.personalBalance(player.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
                assertThat(Ledger.postingsCountForAccount(account)).isEqualTo(initialPostings);
            }
            assertThat(RconClient.send(command + "37")).contains("provider=Treasury", "success=true", "balance=9963.0");
            assertThat(Ledger.personalBalance(player.getUuid()).orElseThrow()).isEqualByComparingTo("9963");
            assertThat(Ledger.postingsCountForAccount(account)).isEqualTo(initialPostings + 1);
            assertThat(RconClient.send("diag vault deposit " + player.getUuid() + " 12"))
                    .contains("provider=Treasury", "success=true", "balance=9975.0");
            assertThat(Ledger.personalBalance(player.getUuid()).orElseThrow()).isEqualByComparingTo("9975");
            assertThat(Ledger.postingsCountForAccount(account)).isEqualTo(initialPostings + 2);
        }
    }

    @Test
    void purchaseRequiresPermissionAndSuccessfulPaymentBeforeTransferringOwnership() throws Exception {
        try (var seller = MockPlayer.connectLocal(Usernames.fresh("RltS"));
             var buyer = MockPlayer.connectLocal(Usernames.fresh("RltB"))) {
            Ledger.waitForPersonalBalance(seller.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(buyer.getUuid(), TIMEOUT);
            String region = Usernames.fresh("Rlt").toLowerCase(Locale.ROOT);
            RconClient.send("diag wgregion world " + region + " 30000 60 30000 30010 80 30010");
            // Registration stores the title holder but deliberately preserves existing WG owners.
            RconClient.send("rg addowner -w world " + region + " " + seller.getUuid());
            Ops.run(seller.getUsername(), () -> {
                seller.chatCommand("/realty register freehold " + region + " --price 37 --titleholder " + seller.getUsername());
                seller.awaitMessageContaining("registered successfully", TIMEOUT);
            });
            Await.until("registered Realty title holder", () -> owners(region),
                    value -> value.equals("owners=[" + seller.getUuid() + "]"), TIMEOUT);
            try (var binding = new RealtyBinding(region)) {
                binding.awaitHolder(seller.getUuid());
                int buyerAccount = Ledger.personalAccountId(buyer.getUuid()).orElseThrow();
                int sellerAccount = Ledger.personalAccountId(seller.getUuid()).orElseThrow();
                int buyerPostings = Ledger.postingsCountForAccount(buyerAccount);
                int sellerPostings = Ledger.postingsCountForAccount(sellerAccount);
                RconClient.send("lp user " + buyer.getUsername() + " permission set realty.command.buy false");
                Await.until("buy permission denied", () -> Perms.check(buyer.getUsername(), "realty.command.buy"),
                        value -> value.contains("has=false"), TIMEOUT);
                buyer.chatCommand("/realty buy " + region);
                Barrier.settle();
                unchanged(seller, buyer, region, sellerAccount, buyerAccount, sellerPostings, buyerPostings);

                Perms.grant(buyer.getUsername(), "realty.command.buy");
                try (var fault = LedgerFaults.rejectDebits(buyerAccount)) {
                    buyer.drainMessages();
                    buyer.chatCommand("/realty buy " + region);
                    buyer.awaitMessageContaining("failed", TIMEOUT);
                    Barrier.settle();
                    unchanged(seller, buyer, region, sellerAccount, buyerAccount, sellerPostings, buyerPostings);
                }
                buyer.drainMessages();
                buyer.chatCommand("/realty buy " + region);
                buyer.awaitMessageContaining("You bought region", TIMEOUT);
                Await.until("new Realty title holder", () -> owners(region),
                        value -> value.equals("owners=[" + buyer.getUuid() + "]"), TIMEOUT);
                assertThat(Ledger.personalBalance(buyer.getUuid()).orElseThrow()).isEqualByComparingTo("9963");
                assertThat(Ledger.personalBalance(seller.getUuid()).orElseThrow()).isEqualByComparingTo("10037");
                assertThat(Ledger.postingsCountForAccount(buyerAccount)).isEqualTo(buyerPostings + 1);
                assertThat(Ledger.postingsCountForAccount(sellerAccount)).isEqualTo(sellerPostings + 1);
                binding.awaitHolder(buyer.getUuid());
            }
        }
    }

    /** Bind configuration only; membership must come from the real Realty provider and scheduler. */
    private static final class RealtyBinding implements AutoCloseable {
        private final java.sql.Connection connection;
        private final int group;
        RealtyBinding(String region) throws Exception {
            connection = java.sql.DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
            try (var q = connection.prepareStatement("INSERT INTO explorer_group(name) VALUES (?)", java.sql.Statement.RETURN_GENERATED_KEYS)) {
                q.setString(1, region); q.executeUpdate();
                try (var r = q.getGeneratedKeys()) { assertThat(r.next()).isTrue(); group = r.getInt(1); }
            }
            try (var q = connection.prepareStatement("INSERT INTO explorer_group_sync(group_id,source,selector) VALUES (?,'realty',?)")) {
                q.setInt(1, group); q.setString(2, RconClient.send("diag worlduuid world").trim() + "/" + region); q.executeUpdate();
            }
        }
        void awaitHolder(java.util.UUID holder) {
            Await.until("Realty group follows title holder", () -> {
                try (var q = connection.prepareStatement("SELECT BIN_TO_UUID(player_uuid_bin) FROM explorer_group_member WHERE group_id=? AND source='realty'")) {
                    q.setInt(1, group);
                    var members = new java.util.HashSet<java.util.UUID>();
                    try (var r = q.executeQuery()) { while (r.next()) members.add(java.util.UUID.fromString(r.getString(1))); }
                    return members;
                } catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
            }, java.util.Set.of(holder)::equals, TIMEOUT);
        }
        public void close() throws Exception {
            try (connection; var q = connection.prepareStatement("DELETE FROM explorer_group WHERE group_id=?")) {
                q.setInt(1, group); q.executeUpdate();
            }
        }
    }

    private static String owners(String region) { return RconClient.send("diag wgowners world " + region).trim(); }

    private static void unchanged(MockPlayer seller, MockPlayer buyer, String region,
                                  int sellerAccount, int buyerAccount, int sellerPostings, int buyerPostings) {
        assertThat(owners(region)).isEqualTo("owners=[" + seller.getUuid() + "]");
        assertThat(Ledger.personalBalance(buyer.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
        assertThat(Ledger.personalBalance(seller.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
        assertThat(Ledger.postingsCountForAccount(buyerAccount)).isEqualTo(buyerPostings);
        assertThat(Ledger.postingsCountForAccount(sellerAccount)).isEqualTo(sellerPostings);
    }
}
