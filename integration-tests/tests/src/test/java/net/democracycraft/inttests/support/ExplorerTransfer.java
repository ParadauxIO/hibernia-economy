package net.democracycraft.inttests.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paradaux.mockclient.MockPlayer;
import java.nio.file.Path;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

/** Invoked while two real OIDC browser sessions remain open. */
public final class ExplorerTransfer {
    public static void main(String[] args) throws Exception {
        Path fixturePath = Path.of(System.getenv("IT_EXPLORER_FIXTURE"));
        var fixture = new ObjectMapper().readTree(fixturePath.toFile());
        // The setup players disconnected after writing this fixture. Respect
        // GriefPrevention's 60-second reconnect cooldown before the first login;
        // do not retry failed connections or alter the provider's protection.
        long reconnectAfter = java.nio.file.Files.getLastModifiedTime(fixturePath).toMillis() + 65_000;
        long waitMillis = reconnectAfter - System.currentTimeMillis();
        if (waitMillis > 0) Thread.sleep(waitMillis);
        String oldName = fixture.get("outsiderName").asText();
        String newName = fixture.get("recipientName").asText();
        String firm = fixture.get("outsiderFirmName").asText();
        int firmId = fixture.get("outsiderFirm").asInt();
        int accountId = fixture.get("firmAccount").asInt();
        Duration timeout = Duration.ofSeconds(10);
        try (var oldOwner = MockPlayer.connectLocal(oldName); var newOwner = MockPlayer.connectLocal(newName)) {
            assertThat(BusinessLedger.firmProprietor(firmId)).hasValue(oldOwner.getUuid());
            var balance = Ledger.accountBalanceById(accountId).orElseThrow();
            int postings = Ledger.postingsCountForAccount(accountId);
            Ops.run(oldName, () -> {
                oldOwner.chatCommand("/business transfer begin " + firm + " " + newName);
                oldOwner.awaitMessageContaining("Confirm transfer", timeout);
                BusinessLedger.waitForActiveTransferToken(firmId, newOwner.getUuid(), timeout);
                String code = BusinessLedger.activeTransferToken(firmId, newOwner.getUuid()).orElseThrow();
                Barrier.settle();
                oldOwner.chatCommand("/business transfer confirm " + firm + " " + newName + " " + code);
                oldOwner.awaitMessageContaining("request has been confirmed", timeout);
                newOwner.awaitMessageContaining("offer to become the proprietor", timeout);
            });
            Ops.run(newName, () -> {
                newOwner.chatCommand("/business transfer complete " + firm + " " + oldName);
                newOwner.awaitMessageContaining("Proprietorship of " + firm + " has been transferred", timeout);
            });
            BusinessLedger.waitForFirmProprietor(firmId, newOwner.getUuid(), timeout);
            assertThat(Ledger.accountBalanceById(accountId).orElseThrow()).isEqualByComparingTo(balance);
            assertThat(Ledger.postingsCountForAccount(accountId)).isEqualTo(postings);
        }
    }
}
