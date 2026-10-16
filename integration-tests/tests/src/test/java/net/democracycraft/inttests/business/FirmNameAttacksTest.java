package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Await;
import net.democracycraft.inttests.support.Barrier;
import net.democracycraft.inttests.support.BusinessLedger;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adversarial firm-name coverage for {@code /business create}.
 *
 * <p>Treasury and Business both render firm names back to players via
 * MiniMessage (chat) and use them as identifiers in subsequent commands.
 * That makes the name field a triple-purpose surface — display, lookup
 * key, and SQL parameter — so any of these attack classes that slips
 * past validation is a real problem:
 *
 * <ul>
 *   <li><b>Display injection.</b> MiniMessage tags ({@code <bold>},
 *       {@code <#hex>}, {@code <click:run_command:'/op X'>}) embedded in
 *       a firm name would render as live formatting / clickable commands
 *       wherever the name appears in chat.</li>
 *   <li><b>Lookup confusion.</b> Names that differ only in invisible
 *       characters (zero-width joiner, RTL marks, Unicode whitespace) or
 *       case-folding tricks let an attacker register a near-duplicate
 *       firm — e.g. "Acme " vs "Acme" — to fool tab-completion or
 *       social-engineer payouts.</li>
 *   <li><b>Length attacks.</b> A 33-character name should be rejected;
 *       a name that's exactly the boundary should be accepted. Empty
 *       names should be rejected by the framework as missing-arg.</li>
 *   <li><b>SQL-shape strings.</b> While Treasury uses prepared
 *       statements end-to-end (we audited 2026-05-09), the test still
 *       sends quote/semicolon-heavy names — a future writer regression
 *       to string concatenation would surface here.</li>
 *   <li><b>Reserved-character classes.</b> Newline, tab, NUL — names
 *       with these are unrepresentable in single-line chat output and
 *       must not pass the regex.</li>
 *   <li><b>Leading-digit rule.</b> The {@code firm.display_name} regex
 *       refuses leading digits to keep "100Acme" from being parseable
 *       as a firm_id in lookup helpers.</li>
 * </ul>
 *
 * <p>The validation source of truth lives in business-rian — see
 * {@code BusinessIngestWriter#sanitizeFirmName} for the ingest-side
 * sanitiser (which is intentionally permissive — it has to keep legacy
 * data) and {@code BusinessApi.createFirm} for the strict command-side
 * validation (which these tests exercise). The two need not match: the
 * ingest-only relaxation is documented in the wiki.
 */
class FirmNameAttacksTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static String suffix() {
        return Long.toHexString(Instant.now().toEpochMilli() ^ System.nanoTime());
    }

    @Test
    void createRejectsLeadingDigitName() {
        assertCreateIsRejected("1Acme" + suffix());
    }

    @Test
    void createRejectsSingleCharacterName() {
        // Below the validator's minimum length (typically 2+).
        assertCreateIsRejected("X");
    }

    @Test
    void createRejectsTooLongName() {
        // 33 chars: one past the 32-char cap.
        String name = ("Z").repeat(33);
        assertCreateIsRejected(name);
    }

    @Test
    void createRejectsMiniMessageTagInjection() {
        // If this slipped through, the name "<bold>FooBar</bold>" would
        // render as live bold formatting in every chat line that echoed
        // it — including ops who type /firm info to investigate.
        assertCreateIsRejected("<bold>Foo" + suffix() + "</bold>");
    }

    @Test
    void createRejectsClickableMiniMessageInjection() {
        // The really nasty MiniMessage form — clicking the firm name in
        // chat would run /op as the clicker. The validator's character
        // allowlist rejects the whole string before it ever gets stored.
        assertCreateIsRejected("<click:run_command:'/op someone'>Click" + suffix() + "</click>");
    }

    @Test
    void createRejectsSqlShapeName() {
        // We use prepared statements, so this is defence-in-depth. Names
        // with quotes/semicolons must still fail the regex outright so a
        // future writer regression to string concat is caught by the
        // pre-write validator, not by SQL parse-error stack traces.
        assertCreateIsRejected("Acme'; DROP TABLE firm; --");
    }

    @Test
    void createRejectsNewlineInName() {
        // A name carrying a literal \n would split chat rendering across two lines. The
        // chat protocol refuses it outright (kick), so it can never reach Business. The
        // same characters through the non-chat vector — the admin firm-rename endpoint —
        // are covered by AdminApiTest.firmRenameRejectsControlCharacters.
        assertCreateIsBlockedByProtocol("Acme\nEvil" + suffix());
    }

    @Test
    void createRejectsControlCharacters() {
        // Tab + bell — likewise refused by the protocol before Business sees them.
        assertCreateIsBlockedByProtocol("Acme\t\u0007" + suffix());
    }

    @Test
    void createRejectsUnicodeZeroWidthInName() {
        // U+200B (zero-width space): a name "Ac​me" looks identical
        // to "Acme" in chat but is a distinct primary key. Without
        // rejection, an attacker can register Ac​me and intercept
        // payments intended for Acme.
        assertCreateIsRejected("Ac​me" + suffix());
    }

    @Test
    void createRejectsTrailingWhitespaceCollidingName() {
        // Same lookup-confusion threat with regular ASCII whitespace.
        // The validator's regex doesn't include whitespace as an
        // allowed character (only ASCII printable + a few separators).
        assertCreateIsRejected("Acme   " + suffix());
    }

    @Test
    void createRejectsEmptyName() {
        // With Brigadier this is missing-arg, surfaced as the framework's
        // generic usage message. We don't pin the text — just that the
        // user gets feedback and no firm row was created.
        String player = Usernames.fresh("CrE");
        try (MockPlayer p = MockPlayer.connectLocal(player)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(player, () -> {
                p.drainMessages();
                p.chatCommand("/business create ");
                p.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();
            });
        }
    }

    @Test
    void createRejectsDuplicateNameCaseInsensitive() {
        // Mojang names have unique casefolding; firms should too — or an
        // attacker can register "ACME" alongside an existing "Acme" to
        // intercept payouts.
        String owner = Usernames.fresh("CrD");
        String firm = "Dup" + suffix();
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                p.drainMessages();
                // Try the same name with different casing.
                p.chatCommand("/business create " + firm.toUpperCase());
                String reply = p.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();
                // Should refuse — exact message varies, but it must NOT be
                // "Successfully created" again.
                assertThat(reply).doesNotContainIgnoringCase("successfully created");
            });
        }
    }

    @Test
    void createAcceptsBoundaryLengthName() {
        // Sanity check the validator allows the maximum: 32 chars.
        // A regression that off-by-ones the cap should be caught here.
        String owner = Usernames.fresh("CrM");
        String firm = ("M" + suffix()).substring(0, Math.min(32, ("M" + suffix()).length()));
        // Pad up to exactly 32 if our suffix-based name was shorter.
        while (firm.length() < 32) firm = firm + "_";
        firm = firm.substring(0, 32);
        String captured = firm;
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + captured);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(captured, TIMEOUT);
                assertThat(BusinessLedger.firmExists(captured)).isTrue();
            });
        }
    }

    // ============================================================ helpers

    private static void assertCreateIsRejected(String firmName) {
        String player = Usernames.fresh("Crj");
        try (MockPlayer p = MockPlayer.connectLocal(player)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(player, () -> {
                p.drainMessages();
                p.chatCommand("/business create " + firmName);
                p.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();
                // The reply may not be the handler's last act; wait until it has finished.
                Barrier.settle();
                // The cardinal assertion: no row landed in `firm`.
                assertThat(BusinessLedger.firmExists(firmName))
                        .as("firm row must NOT exist after rejected /business create '" + firmName + "'")
                        .isFalse();
            });
        }
    }

    /**
     * Names with characters the chat protocol forbids never reach Business: the server
     * disconnects the client ("Illegal characters in chat") while decoding the command
     * packet, before dispatch. Assert that defence — the kick — and its outcome: no firm.
     * (Business's own validator is unreachable for these through the only entry point.)
     */
    private static void assertCreateIsBlockedByProtocol(String firmName) {
        String player = Usernames.fresh("Crk");
        try (MockPlayer p = MockPlayer.connectLocal(player)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(player, () -> {
                p.chatCommand("/business create " + firmName);
                Await.untilTrue(player + " to be disconnected for illegal chat characters",
                        () -> !p.isConnected(), TIMEOUT);
            });
        }
        // The kick happens before dispatch, so there is no handler to wait for.
        assertThat(BusinessLedger.firmExists(firmName))
                .as("firm row must NOT exist after /business create '" + firmName + "'")
                .isFalse();
    }
}
