package io.paradaux.common;

import java.sql.SQLException;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/** Safe user feedback for namespace errors nested by JDBC/MyBatis/Spring. */
public final class NameReservationErrors {
    private static final String CONFLICT =
            "That name is reserved by another identity. Choose another name or ask an administrator.";

    private NameReservationErrors() {}

    /** Classify across plugin classloaders using the preserved JDBC cause. */
    public static boolean isConflict(Throwable error) {
        return userMessage(error).filter(CONFLICT::equals).isPresent();
    }

    public static Optional<String> userMessage(Throwable error) {
        @Nullable Throwable cause = error;
        for (int depth = 0; cause != null && depth < 32; depth++, cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                String detail = sql.getMessage();
                if (sql.getErrorCode() == 1062
                        && detail != null
                        && (detail.contains("uq_firm_reserved_display_name")
                                || detail.contains("uq_firm_shop_alias"))) {
                    return Optional.of(CONFLICT);
                }
                if (!"45000".equals(sql.getSQLState())) continue;
                String message = sql.getMessage();
                if (message != null && message.contains("GLOBAL_NAME_CONFLICT:")) {
                    return Optional.of(CONFLICT);
                }
                if (message != null && message.contains("GLOBAL_NAME_INVALID:")) {
                    return Optional.of("Choose a nonblank name.");
                }
            }
        }
        return Optional.empty();
    }
}
