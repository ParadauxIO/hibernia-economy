package net.democracycraft.inttests.ingest;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Await;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Perms;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E coverage of {@code /treasury admin ingest essentialsx} — the
 * EssentialsX → Treasury data import path implemented by the
 * standalone TreasuryIngest plugin.
 *
 * <p>Each test plants one or more synthetic EssentialsX userdata YAML
 * files under {@code plugins/Essentials/userdata/}, runs the admin
 * command from an in-game player (rcon swallows {@code @Async} command
 * output), and asserts post-conditions against the live MariaDB:
 *
 * <ul>
 *   <li>fresh UUID → PERSONAL account created with the imported
 *       balance, no Treasury starting-balance grant;</li>
 *   <li>{@code player_login_times} seeded so the next real join
 *       computes per-login balance tax against the legacy timestamp;</li>
 *   <li>the {@code TreasuryIngest} GOVERNMENT faucet's balance drops by
 *       the sum of imports (negative, since {@code credit_limit = -1});</li>
 *   <li>re-running the command does not double-fund (skipped — the
 *       PERSONAL account now exists).</li>
 * </ul>
 *
 * <p><b>Disabled:</b> the code under test is not in the integration stack.
 * {@code ops/integration/run.sh} installs only this repo's plugins, and the
 * EssentialsX provider lives in the standalone TreasuryIngest plugin (a
 * separate repository). The tests also plant files under a hardcoded
 * dev-server path ({@link #USERDATA_DIR}) rather than the run's work dir.
 * Treasury's side of the flow — provider lookup and dispatch — is covered by
 * {@code TreasuryAdminTest.adminIngestDispatchesToARegisteredProvider}
 * through DcDiag's stub provider. To re-enable: have run.sh fetch a pinned
 * TreasuryIngest build and point {@link #USERDATA_DIR} at
 * {@code $IT_WORK_DIR/server/plugins/Essentials/userdata}.
 */
@Disabled("Needs the standalone TreasuryIngest plugin, which is not in this repo and not installed by "
        + "ops/integration/run.sh, plus a hardcoded dev-server userdata path. Follow-up ticket: install a "
        + "pinned TreasuryIngest in the stack and re-enable. Dispatch is covered by TreasuryAdminTest.")
class EssentialsXIngestTest {

    private static final Path USERDATA_DIR = Path.of(
            "/home/debian/workspace/server/plugins/Essentials/userdata");
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final List<Path> plantedFiles = new ArrayList<>();

    @BeforeEach
    void ensureUserdataDir() throws Exception {
        Files.createDirectories(USERDATA_DIR);
    }

    @AfterEach
    void cleanupPlantedFiles() {
        for (Path p : plantedFiles) {
            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
        }
        plantedFiles.clear();
    }

    @Test
    void freshPlayerGetsImportedBalanceAndLoginTimestamp() {
        UUID importedUuid = UUID.randomUUID();
        BigDecimal money = new BigDecimal("4321.78");
        long loginEpochSeconds = 1_700_000_000L;
        plantUserdata(importedUuid, "ImportedAlice", money, loginEpochSeconds * 1_000L, false);

        BigDecimal faucetBefore = Ledger.governmentBalance("TreasuryIngest").orElse(BigDecimal.ZERO);
        assertThat(Ledger.personalAccountExists(importedUuid))
                .as("synthetic UUID must not already have a PERSONAL account")
                .isFalse();

        runIngestAsAdmin();

        // Account created with the EssentialsX balance — never touched
        // resolveOrCreatePersonal, so no $10,000 starting-balance grant
        // showed up.
        Ledger.waitForPersonalBalance(importedUuid, money, TIMEOUT);

        // player_login_times now contains the legacy timestamp so the next
        // real join's prorated balance tax measures from there, not from
        // the join itself.
        Await.untilEquals("last login epoch of " + importedUuid,
                () -> Ledger.lastLoginEpoch(importedUuid).orElse(null), loginEpochSeconds, TIMEOUT);

        // The faucet absorbed the cost as overdraft (credit_limit = -1).
        BigDecimal faucetAfter = Ledger.governmentBalance("TreasuryIngest").orElse(BigDecimal.ZERO);
        assertThat(faucetAfter)
                .as("faucet balance should drop by exactly the imported amount")
                .isEqualByComparingTo(faucetBefore.subtract(money));
    }

    @Test
    void existingPersonalAccountIsLeftAlone() throws Exception {
        // Simulate a player who is *already* on Treasury: have them join
        // (gives them the configured starting balance), then drop an
        // EssentialsX userdata file claiming a different amount. Ingest
        // must SKIP — never touch their live ledger state.
        String name = Usernames.fresh("Iexis");
        UUID activeUuid = offlineUuid(name);
        BigDecimal balanceBefore;
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            balanceBefore = Ledger.waitForPersonalBalance(activeUuid, TIMEOUT);
        }
        assertThat(balanceBefore).isGreaterThan(BigDecimal.ZERO);

        plantUserdata(activeUuid, name, new BigDecimal("99999.99"), 1_650_000_000_000L, false);

        BigDecimal faucetBefore = Ledger.governmentBalance("TreasuryIngest").orElse(BigDecimal.ZERO);
        runIngestAsAdmin();

        // Confirm SKIP. runIngestAsAdmin returned on "Ingest complete", the handler's last act.
        assertThat(Ledger.personalBalance(activeUuid))
                .as("existing PERSONAL accounts must not be touched by ingest")
                .contains(balanceBefore);
        assertThat(Ledger.governmentBalance("TreasuryIngest").orElse(BigDecimal.ZERO))
                .as("faucet must not be debited when ingest skipped the player")
                .isEqualByComparingTo(faucetBefore);
    }

    @Test
    void rerunningIngestIsIdempotent() {
        UUID importedUuid = UUID.randomUUID();
        BigDecimal money = new BigDecimal("250.00");
        plantUserdata(importedUuid, "ImportedBob", money, 1_700_000_001_000L, false);

        runIngestAsAdmin();
        Ledger.waitForPersonalBalance(importedUuid, money, TIMEOUT);
        BigDecimal faucetAfterFirst = Ledger.governmentBalance("TreasuryIngest").orElse(BigDecimal.ZERO);

        // Second run: the PERSONAL account now exists, so the player must
        // be SKIPPED. Faucet stays put. (runIngestAsAdmin returned on "Ingest complete".)
        runIngestAsAdmin();
        assertThat(Ledger.personalBalance(importedUuid))
                .as("second ingest must not double-fund the player")
                .contains(money);
        assertThat(Ledger.governmentBalance("TreasuryIngest").orElse(BigDecimal.ZERO))
                .as("second ingest must not debit the faucet again")
                .isEqualByComparingTo(faucetAfterFirst);
    }

    private void runIngestAsAdmin() {
        String adminName = Usernames.fresh("Iadm");
        try (MockPlayer p = MockPlayer.connectLocal(adminName)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(adminName, () -> {
                Perms.grant(adminName, "treasury.admin.ingest");
                p.chatCommand("/treasury admin ingest essentialsx");
                p.awaitMessageContaining("Ingest complete", TIMEOUT);
                p.drainMessages();
            });
        }
    }

    private void plantUserdata(UUID uuid, String name, BigDecimal money,
                               long loginEpochMillis, boolean npc) {
        String yaml = "money: '" + money.toPlainString() + "'\n"
                + "timestamps:\n"
                + "  login: " + loginEpochMillis + "\n"
                + "  logout: " + (loginEpochMillis - 60_000) + "\n"
                + "last-account-name: " + name + "\n"
                + "npc: " + npc + "\n";
        Path file = USERDATA_DIR.resolve(uuid.toString() + ".yml");
        try {
            Files.writeString(file, yaml,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            plantedFiles.add(file);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Computes Minecraft's offline-mode UUID for a name. Mirrors the
     * server-side derivation in {@code OfflinePlayerInfo}: name UTF-8
     * bytes prefixed with "OfflinePlayer:" hashed as a name-based MD5
     * UUID (version 3).
     */
    private static UUID offlineUuid(String name) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] bytes = ("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] hash = md.digest(bytes);
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x30);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            long msb = 0L; long lsb = 0L;
            for (int i = 0; i < 8; i++) msb = (msb << 8) | (hash[i] & 0xff);
            for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (hash[i] & 0xff);
            return new UUID(msb, lsb);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // Keep the unused import slots from being collapsed by IDE tooling.
    @SuppressWarnings("unused")
    private static Set<Object> __keepImports() { return new HashSet<>(); }
}
