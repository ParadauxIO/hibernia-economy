package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/** The command layer, unlike the ledger service, must reject archived destinations. */
class ArchivedTypedPaymentTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @ValueSource(strings = {"government", "account"})
    void typedPaymentCannotDepositIntoArchivedGovernment(String type) {
        try (MockPlayer admin = MockPlayer.connectLocal(Usernames.fresh("ArAdm"));
             MockPlayer payer = MockPlayer.connectLocal(Usernames.fresh("ArPay"))) {
            Ledger.waitForPersonalBalance(admin.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(payer.getUuid(), TIMEOUT);
            String name = Usernames.fresh("ArGov");
            Ops.run(admin.getUsername(), () -> {
                admin.chatCommand("/gov account create " + name);
                Ledger.waitForGovernmentAccount(name, TIMEOUT);
                admin.chatCommand("/paya government " + name + " 23.45 ArchiveSeed");
                Ledger.waitForGovernmentBalance(name, new BigDecimal("23.45"), TIMEOUT);
                admin.chatCommand("/gov account archive " + name);
                Barrier.settle();
            });
            assertThat(Ledger.governmentAccountIsArchived(name)).isTrue();
            // Active-only account lookup intentionally cannot resolve the archived ID.
            int account;
            try (var c = java.sql.DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
                 var s = c.prepareStatement("SELECT account_id FROM accounts WHERE account_type='GOVERNMENT' AND display_name=?")) {
                s.setString(1, name);
                try (var r = s.executeQuery()) { assertThat(r.next()).isTrue(); account = r.getInt(1); }
            } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
            long checkpoint = Ledger.transactionCheckpoint();
            payer.drainMessages();
            payer.chatCommand("/paya " + type + " " + (type.equals("account") ? account : name) + " 1 ArchivedPayment");
            Barrier.settle();
            assertAll(
                    () -> assertThat(payer.collectedMessages()).noneMatch(m -> m.contains("Paid")),
                    () -> assertThat(Ledger.personalBalance(payer.getUuid()).orElseThrow()).isEqualByComparingTo("10000"),
                    () -> assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo("23.45"),
                    () -> assertThat(Ledger.initiatedPostingsAfter(payer.getUuid(), checkpoint)).isEmpty());
        }
    }
}
