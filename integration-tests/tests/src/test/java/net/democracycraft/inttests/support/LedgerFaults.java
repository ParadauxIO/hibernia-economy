package net.democracycraft.inttests.support;

import java.sql.DriverManager;
import java.sql.SQLException;

/** Scoped database failure injection for the runner's disposable, loopback-only stack. */
public final class LedgerFaults {
    public static AutoCloseable rejectDebits(int accountId) {
        if (accountId <= 0 || System.getenv("IT_SERVER_DIR") == null
                || !DevServer.DB_URL.matches("jdbc:mariadb://(127\\.0\\.0\\.1|localhost):[0-9]+/.*")) {
            throw new IllegalStateException("Ledger faults require the disposable runner and a loopback database");
        }
        String trigger = "it_reject_debit_" + Long.toUnsignedString(System.nanoTime(), 36);
        execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON ledger_postings FOR EACH ROW BEGIN "
                + "IF NEW.account_id = " + accountId + " AND NEW.amount < 0 THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'PAR499 injected debit failure'; "
                + "END IF; END");
        return () -> execute("DROP TRIGGER " + trigger);
    }

    private static void execute(String sql) {
        try (var c = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             var statement = c.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("Could not install/remove scoped ledger fault", e);
        }
    }

    private LedgerFaults() {}
}
