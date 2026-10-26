package net.democracycraft.inttests;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.time.Duration;

/**
 * Cross-plugin permission-denial coverage. Mock players run protected
 * commands without the permissions {@code Ops.run} grants. Brigadier's
 * {@code requires(...)} predicate (set by Hibernia's @Permission) rejects
 * the command before dispatch with the framework's standard reply.
 */
class PermissionDenialTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void unprivilegedEcoGiveDenied() {
        String name = Usernames.fresh("Ped");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            // No Ops.run wrapper — player has only default permissions.
            // Brigadier's requires(...) hides the literal entirely; Paper sends
            // the vanilla translation key command.unknown.command.
            p.drainMessages();
            p.chatCommand("/eco give " + name + " 100");
            p.awaitMessage(m -> m.toLowerCase().contains("permission")
                    || m.toLowerCase().contains("unknown")
                    || m.contains("command.unknown.command"), TIMEOUT);
        }
    }

    @Test
    void unprivilegedGovAccountCreateDenied() {
        String name = Usernames.fresh("Pgd");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            p.drainMessages();
            p.chatCommand("/gov account create unauthorised");
            p.awaitMessage(m -> m.toLowerCase().contains("permission")
                    || m.toLowerCase().contains("access")
                    || m.toLowerCase().contains("unknown")
                    || m.contains("command.unknown.command"), TIMEOUT);
        }
    }

    @Test
    void unprivilegedFineIssueDenied() {
        String name = Usernames.fresh("Pfd");
        String target = Usernames.fresh("Pft");
        try (MockPlayer p = MockPlayer.connectLocal(name);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            p.drainMessages();
            p.chatCommand("/fine issue GovernmentFines " + target + " 100 reason");
            p.awaitMessage(m -> m.toLowerCase().contains("permission")
                    || m.toLowerCase().contains("unknown")
                    || m.contains("command.unknown.command"), TIMEOUT);
        }
    }

    @Test
    void unprivilegedBusinessTaxExemptDenied() {
        String name = Usernames.fresh("Pbd");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            p.drainMessages();
            p.chatCommand("/business tax exempt SomeFirm true");
            p.awaitMessage(m -> m.toLowerCase().contains("permission")
                    || m.toLowerCase().contains("unknown")
                    || m.contains("command.unknown.command"), TIMEOUT);
        }
    }
}
