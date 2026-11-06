package io.paradaux.treasury.internal.utils;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

/**
 * Programmatically sets the log4j level for the Treasury package hierarchy.
 *
 * <p>Paper bundles log4j 2 as the SLF4J binding, so adjusting the level here
 * filters every {@code @Slf4j}-generated logger under {@code io.paradaux.treasury}
 * before its output reaches the console. The default WARN keeps routine
 * lifecycle and per-transfer logs out of the way during normal operation.
 */
public final class LoggingConfigurer {

    private static final String PACKAGE_ROOT = "io.paradaux.treasury";

    private LoggingConfigurer() {}

    /**
     * Applies the given level to the {@code io.paradaux.treasury} logger
     * hierarchy. Unrecognised level names fall back to WARN.
     */
    public static void apply(String levelName) {
        Level level = Level.toLevel(levelName, Level.WARN);

        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        LoggerConfig existing = config.getLoggerConfig(PACKAGE_ROOT);

        if (PACKAGE_ROOT.equals(existing.getName())) {
            existing.setLevel(level);
        } else {
            LoggerConfig fresh = new LoggerConfig(PACKAGE_ROOT, level, true);
            config.addLogger(PACKAGE_ROOT, fresh);
        }
        ctx.updateLoggers();
    }
}
