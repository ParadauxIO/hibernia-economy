package net.democracycraft.inttests.support;

/**
 * Connection details for the dev server. Defaults match
 * {@code wiki/dev-server.md}. Override via system properties or environment
 * variables when running against a different setup.
 *
 * <p>Properties (highest precedence): {@code -Ddevserver.host}, {@code -Ddevserver.port},
 * {@code -Ddevserver.db.url}, {@code -Ddevserver.db.user}, {@code -Ddevserver.db.password}.
 *
 * <p>Env vars: {@code DEVSERVER_HOST}, {@code DEVSERVER_PORT}, {@code DEVSERVER_DB_URL},
 * {@code DEVSERVER_DB_USER}, {@code DEVSERVER_DB_PASSWORD}.
 */
public final class DevServer {

    public static final String HOST = resolve("devserver.host", "DEVSERVER_HOST", "127.0.0.1");
    public static final int PORT = Integer.parseInt(resolve("devserver.port", "DEVSERVER_PORT", "25565"));
    public static final String DB_URL = resolve("devserver.db.url", "DEVSERVER_DB_URL",
            "jdbc:mariadb://localhost:3306/economy");
    public static final String DB_USER = resolve("devserver.db.user", "DEVSERVER_DB_USER", "root");
    public static final String DB_PASSWORD = resolve("devserver.db.password", "DEVSERVER_DB_PASSWORD", "dev");

    private DevServer() {}

    private static String resolve(String prop, String env, String def) {
        String v = System.getProperty(prop);
        if (v != null) return v;
        v = System.getenv(env);
        if (v != null) return v;
        return def;
    }
}
