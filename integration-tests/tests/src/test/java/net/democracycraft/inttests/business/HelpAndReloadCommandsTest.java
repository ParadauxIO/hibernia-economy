package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /business} help and reload.
 *
 * <p>Help is no longer a set of hand-maintained topic pages
 * ({@code /business help firms|staff|requests|roles|accounts|misc}, PAR-262):
 * the framework's HelpGenerator renders one paginated listing straight from the
 * route index — header {@code /db help (page N/M)}, then one
 * {@code /db <route> — <description>} line per route. Each former topic is
 * therefore asserted by paging through {@code /business help <page>} and
 * checking that topic's routes are listed.
 */
class HelpAndReloadCommandsTest {

    /** Header of every generated help page; "db" is the command's canonical root. */
    private static final Pattern HEADER = Pattern.compile("/db help \\(page (\\d+)/(\\d+)\\)");

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** Sends {@code /business help}, then every further page; returns all pages' text. */
    private static String fullHelp(MockPlayer p) {
        p.drainMessages();
        p.chatCommand("/business help");
        String first = p.awaitMessage(m -> HEADER.matcher(m).find(), TIMEOUT);
        Matcher header = HEADER.matcher(first);
        assertThat(header.find()).isTrue();
        assertThat(header.group(1)).as("bare help opens on page 1").isEqualTo("1");
        int pages = Integer.parseInt(header.group(2));

        StringBuilder all = new StringBuilder(first);
        for (int page = 2; page <= pages; page++) {
            p.chatCommand("/business help " + page);
            all.append('\n').append(
                    p.awaitMessageContaining("help (page " + page + "/" + pages + ")", TIMEOUT));
        }
        return all.toString();
    }

    private static void assertHelpLists(String tag, String... routes) {
        String name = Usernames.fresh(tag);
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(name, () -> assertThat(fullHelp(p)).contains(routes));
        }
    }

    @Test
    void rootHelpShowsHelpHeader() {
        String name = Usernames.fresh("Hr");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(name, () -> {
                p.chatCommand("/business");
                String page = p.awaitMessage(m -> HEADER.matcher(m).find(), TIMEOUT);
                assertThat(page).contains("/db help (page 1/");
                // The page body is the generated route listing, not just a header.
                assertThat(page).contains("\n/db ");
            });
        }
    }

    @Test
    void helpPageArgumentSelectsThatPage() {
        String name = Usernames.fresh("Hp");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(name, () -> {
                p.chatCommand("/business help 2");
                String page = p.awaitMessage(m -> HEADER.matcher(m).find(), TIMEOUT);
                assertThat(page).contains("/db help (page 2/");
            });
        }
    }

    @Test
    void helpFirmsShowsFirmsHelp() {
        assertHelpLists("Hf", "/db create <firm>", "/db disband <firm>", "/db info <firm>",
                "/db attribute set hq <firm> <plot>");
    }

    @Test
    void helpStaffShowsStaffHelp() {
        assertHelpLists("Hs", "/db fire <firm> <user>", "/db resign <firm>",
                "/db staff promote <firm> <user>", "/db staff demote <firm> <user>",
                "/db staff list <firm>");
    }

    @Test
    void helpRequestsShowsRequestsHelp() {
        assertHelpLists("Hq", "/db offer <firm> <user>", "/db offer accept <firm>",
                "/db transfer begin <firm> <user>", "/db transfer cancel <firm> <user>");
    }

    @Test
    void helpRolesShowsRolesHelp() {
        assertHelpLists("Ho", "/db staff role create <firm> <role> <order>",
                "/db staff role delete <firm> <role>",
                "/db staff role permission add <firm> <role> <permission>");
    }

    @Test
    void helpAccountsShowsAccountsHelp() {
        assertHelpLists("Ha", "/db account create <firm> <name>",
                "/db account setdefault <firm> <accountId>",
                "/db account addmember <firm> <accountId> <player>");
    }

    @Test
    void helpMiscShowsMiscHelp() {
        assertHelpLists("Hm", "/db balance <firm>", "/db deposit <firm> <amount>",
                "/db withdraw <firm> <amount>", "/db pay player <firm> <player> <amount>",
                "/db transactions <firm>");
    }

    @Test
    void reloadReportsSuccess() {
        String name = Usernames.fresh("Rl");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(name, () -> {
                p.chatCommand("/business reload");
                p.awaitMessageContaining("Reloaded messages.properties", TIMEOUT);
            });
        }
    }
}
