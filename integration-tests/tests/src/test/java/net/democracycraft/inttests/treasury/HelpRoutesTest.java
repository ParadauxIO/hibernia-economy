package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for the help routes on every Treasury command.
 *
 * <p>The user-visible help surface has two layers:
 *
 * <ol>
 *   <li>Generated indexes: {@code /treasury help [page]} and
 *       {@code /government} / {@code /government help [page]} are rendered at
 *       runtime by the framework's {@code HelpGenerator} from the routes that
 *       are actually registered — header {@code "/<root> help (page n/N)"},
 *       then one {@code "/<root> <route> — <description>"} line per route, 8
 *       per page, filtered to the routes the viewer has permission to run.
 *       There are no per-topic subroutes any more ({@code /tr help pay},
 *       {@code /gov help account} …): the only argument is a page number.</li>
 *   <li>Curated per-command help: {@code @Route("")} / {@code @Route("help")}
 *       on FineCommand, EcoCommand, TaxCommand, BalanceCommand, BaltopCommand,
 *       TransactionsCommand and PayCommand send the {@code treasury.help.*}
 *       message for that command, so {@code /fine} or {@code /pay help} is
 *       where a topic's help lives.</li>
 * </ol>
 *
 * <p>Curated-help tests assert a substring known to be in the corresponding
 * {@code treasury.help.*} message — chosen to survive minor copy edits.
 * Index tests assert the routes a group of subcommands must list. The chat
 * receipt itself is the assertion: no chat ⇒ no route registered or no
 * permission ⇒ test fails clearly.
 */
class HelpRoutesTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final Pattern PAGE_HEADER = Pattern.compile("help \\(page (\\d+)/(\\d+)\\)");

    // ============================================================ /treasury help

    @Test
    void treasuryHelpIndexListsTreasuryRoutes() {
        // A regular player sees the generated index of the /treasury routes
        // they may run — and none of the admin ones.
        try (MockPlayer p = connect("Thi")) {
            p.drainMessages();
            p.chatCommand("/tr help");
            String r = p.awaitMessageContaining("/treasury help (page 1/", TIMEOUT);
            assertThat(r)
                    .contains("/treasury — ")
                    .contains("/treasury help — ")
                    .contains("/treasury help <page> — ")
                    .doesNotContain("/treasury admin")
                    .doesNotContain("/treasury reload");
        }
    }

    @Test
    void treasuryHelpIndexShowsAdminRoutesToAdmins() {
        try (MockPlayer p = connect("Tha")) {
            Ops.run(p.getUsername(), () -> {
                String all = allPages(p, "/tr help", "/treasury help");
                assertThat(all)
                        .contains("/treasury admin transfer <fromType> <from> <toType> <to> <amount>")
                        .contains("/treasury admin balance <type> <id>")
                        .contains("/treasury admin info <type> <id>")
                        .contains("/treasury admin ingest <source>")
                        .contains("/treasury reload");
            });
        }
    }

    @Test
    void treasuryHelpClampsOutOfRangePage() {
        try (MockPlayer p = connect("Thc")) {
            p.drainMessages();
            p.chatCommand("/tr help 99");
            String r = p.awaitMessageContaining("/treasury help (page", TIMEOUT);
            Matcher m = PAGE_HEADER.matcher(r);
            assertThat(m.find()).isTrue();
            assertThat(m.group(1)).as("page 99 clamps to the last page").isEqualTo(m.group(2));
        }
    }

    // The per-topic help that used to hang off /treasury help <topic> is each
    // command's own help route.

    @Test
    void balanceHelpShowsBalanceTopHelp() {
        assertHelpContains("/balance help", "Balance", "/baltop");
    }

    @Test
    void payHelpShowsPayUsage() {
        assertHelpContains("/pay help", "Pay Help", "target");
    }

    @Test
    void transactionsHelpShowsExportUsage() {
        assertHelpContains("/transactions help", "Transactions Help", "export");
    }

    @Test
    void ecoHelpShowsGiveUsage() {
        assertAdminHelpContains("/eco help", "Eco", "give");
    }

    @Test
    void fineHelpShowsIssueUsage() {
        // /fine issue names the government account the fine is paid into.
        assertAdminHelpContains("/fine help", "Fine Help", "issue <account> <player> <amount> <reason>");
    }

    @Test
    void taxHelpShowsStatusUsage() {
        assertAdminHelpContains("/tax help", "Tax Help", "status");
    }

    // ============================================================ /government help

    @Test
    void govHelpIndexCoversEverySubcommandGroup() {
        // The index spans several pages; between them it lists the account,
        // member, viewer, authorizer and transfer groups.
        try (MockPlayer p = connect("Thg")) {
            Ops.run(p.getUsername(), () -> {
                String all = allPages(p, "/gov help", "/government help");
                assertThat(all)
                        .contains("/government account create")
                        .contains("/government account member add")
                        .contains("/government account viewer add")
                        .contains("/government account auth add")
                        .contains("/government account transfer")
                        .contains("/government payout");
            });
        }
    }

    @Test
    void govRootShowsHelpIndex() {
        // The user's specific complaint — /government no longer goes silent.
        try (MockPlayer p = connect("Gvr")) {
            p.drainMessages();
            Ops.run(p.getUsername(), () -> {
                p.chatCommand("/government");
                String r = p.awaitMessageContaining("/government help (page 1/", TIMEOUT);
                assertThat(r).contains("/government account");
            });
        }
    }

    @Test
    void govHelpRouteShowsHelpIndex() {
        try (MockPlayer p = connect("Gvh")) {
            p.drainMessages();
            Ops.run(p.getUsername(), () -> {
                p.chatCommand("/gov help");
                String r = p.awaitMessageContaining("/government help (page 1/", TIMEOUT);
                assertThat(r).contains("/government account");
            });
        }
    }

    @Test
    void govHelpListsAccountSubcommands() {
        try (MockPlayer p = connect("Gva")) {
            Ops.run(p.getUsername(), () -> {
                String all = allPages(p, "/gov help", "/government help");
                assertThat(all)
                        .contains("/government account create <name>")
                        .contains("/government account archive <name>")
                        .contains("/government account list")
                        .contains("/government account balance <name>")
                        .contains("/government account history <name>");
            });
        }
    }

    @Test
    void govHelpListsMemberSubcommands() {
        try (MockPlayer p = connect("Gvm")) {
            Ops.run(p.getUsername(), () -> {
                String all = allPages(p, "/gov help", "/government help");
                assertThat(all)
                        .contains("/government account member add <account> <player>")
                        .contains("/government account member remove <account> <player>")
                        .contains("/government account member addgroup <account> <group>")
                        .contains("/government account member removegroup <account> <group>")
                        .contains("/government account member list <account>");
            });
        }
    }

    @Test
    void govHelpListsAuthSubcommands() {
        try (MockPlayer p = connect("Gvu")) {
            Ops.run(p.getUsername(), () -> {
                String all = allPages(p, "/gov help", "/government help");
                assertThat(all)
                        .contains("/government account auth add <account> <player>")
                        .contains("/government account auth remove <account> <player>")
                        .contains("/government account auth addgroup <account> <group>")
                        .contains("/government account auth removegroup <account> <group>")
                        .contains("/government account auth list <account>");
            });
        }
    }

    @Test
    void govHelpListsTransferSubcommands() {
        try (MockPlayer p = connect("Gvt")) {
            Ops.run(p.getUsername(), () -> {
                String all = allPages(p, "/gov help", "/government help");
                assertThat(all)
                        .contains("/government account transfer <from> <to> <amount>")
                        .contains("/government pay <from> <to> <amount>")
                        .contains("/government payout <from> <to> <amount>");
            });
        }
    }

    // ============================================================ per-command roots

    @Test
    void fineRootShowsHelp() {
        try (MockPlayer p = connect("Fnr")) {
            p.drainMessages();
            Ops.run(p.getUsername(), () -> {
                p.chatCommand("/fine");
                p.awaitMessageContaining("Fine Help", TIMEOUT);
            });
        }
    }

    @Test
    void fineHelpRouteShowsHelp() {
        try (MockPlayer p = connect("Fnh")) {
            p.drainMessages();
            Ops.run(p.getUsername(), () -> {
                p.chatCommand("/fine help");
                p.awaitMessageContaining("Fine Help", TIMEOUT);
            });
        }
    }

    @Test
    void ecoRootShowsHelp() {
        try (MockPlayer p = connect("Ecr")) {
            p.drainMessages();
            Ops.run(p.getUsername(), () -> {
                p.chatCommand("/eco");
                p.awaitMessageContaining("Eco", TIMEOUT);
            });
        }
    }

    @Test
    void ecoHelpRouteShowsHelp() {
        try (MockPlayer p = connect("Ech")) {
            p.drainMessages();
            Ops.run(p.getUsername(), () -> {
                p.chatCommand("/eco help");
                p.awaitMessageContaining("Eco", TIMEOUT);
            });
        }
    }

    @Test
    void taxRootShowsHelp() {
        try (MockPlayer p = connect("Txr")) {
            p.drainMessages();
            Ops.run(p.getUsername(), () -> {
                p.chatCommand("/tax");
                p.awaitMessageContaining("Tax Help", TIMEOUT);
            });
        }
    }

    @Test
    void taxHelpRouteShowsHelp() {
        try (MockPlayer p = connect("Txh")) {
            p.drainMessages();
            Ops.run(p.getUsername(), () -> {
                p.chatCommand("/tax help");
                p.awaitMessageContaining("Tax Help", TIMEOUT);
            });
        }
    }

    @Test
    void payRootShowsHelp() {
        try (MockPlayer p = connect("Pyr")) {
            p.drainMessages();
            p.chatCommand("/pay");
            p.awaitMessageContaining("Pay Help", TIMEOUT);
        }
    }

    @Test
    void payHelpRouteShowsHelp() {
        try (MockPlayer p = connect("Pyh")) {
            p.drainMessages();
            p.chatCommand("/pay help");
            p.awaitMessageContaining("Pay Help", TIMEOUT);
        }
    }

    @Test
    void balanceHelpRouteShowsHelp() {
        // /bal alone shows the player's balance; /bal help is the new help
        // route. The literal `help` must take precedence over <target>.
        try (MockPlayer p = connect("Blh")) {
            p.drainMessages();
            p.chatCommand("/bal help");
            p.awaitMessageContaining("Balance", TIMEOUT);
        }
    }

    @Test
    void baltopHelpRouteShowsHelp() {
        try (MockPlayer p = connect("Bth")) {
            p.drainMessages();
            p.chatCommand("/baltop help");
            p.awaitMessageContaining("Balance", TIMEOUT);
        }
    }

    @Test
    void transactionsHelpRouteShowsHelp() {
        try (MockPlayer p = connect("Trh")) {
            p.drainMessages();
            p.chatCommand("/transactions help");
            p.awaitMessageContaining("Transactions Help", TIMEOUT);
        }
    }

    // ============================================================ helpers

    private static MockPlayer connect(String prefix) {
        return MockPlayer.connectLocal(Usernames.fresh(prefix));
    }

    private static void assertHelpContains(String command, String headerFragment, String mustInclude) {
        try (MockPlayer p = connect("Trh")) {
            p.drainMessages();
            p.chatCommand(command);
            String r = p.awaitMessageContaining(headerFragment, TIMEOUT);
            assertThat(r).contains(mustInclude);
        }
    }

    /** As {@link #assertHelpContains} for commands gated behind an admin permission. */
    private static void assertAdminHelpContains(String command, String headerFragment, String mustInclude) {
        try (MockPlayer p = connect("Trh")) {
            Ops.run(p.getUsername(), () -> {
                p.drainMessages();
                p.chatCommand(command);
                String r = p.awaitMessageContaining(headerFragment, TIMEOUT);
                assertThat(r).contains(mustInclude);
            });
        }
    }

    /**
     * Walk every page of a generated help index ({@code <command> [page]}) and
     * return the pages concatenated. {@code headerRoot} is the canonical root the
     * header prints (aliases render under it), e.g. {@code "/government help"}.
     */
    private static String allPages(MockPlayer p, String command, String headerRoot) {
        p.drainMessages();
        p.chatCommand(command);
        String first = p.awaitMessageContaining(headerRoot + " (page 1/", TIMEOUT);
        Matcher m = PAGE_HEADER.matcher(first);
        assertThat(m.find()).as("help header carries a page count: %s", first).isTrue();
        int pages = Integer.parseInt(m.group(2));
        StringBuilder all = new StringBuilder(first);
        for (int page = 2; page <= pages; page++) {
            p.drainMessages();
            p.chatCommand(command + " " + page);
            all.append('\n').append(
                    p.awaitMessageContaining(headerRoot + " (page " + page + "/" + pages + ")", TIMEOUT));
        }
        return all.toString();
    }
}
