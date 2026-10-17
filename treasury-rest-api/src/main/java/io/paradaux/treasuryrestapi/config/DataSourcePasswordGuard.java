package io.paradaux.treasuryrestapi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Fail fast if the production profile is running against the documented default
 * database password (ADT-127). application.yaml defaults the password to the
 * literal {@code password} for local dev convenience; the JWT secret has an
 * equivalent fatal guard (see {@link JwtConfig}) but the DB password did not, so
 * a prod deployment that forgot to set {@code DB_PASSWORD=password} (or set it to
 * the placeholder) would silently boot against the shared money DB with a guessable
 * credential. Dev/default profiles are unaffected — the check only applies in prod.
 */
@Component
public class DataSourcePasswordGuard {

    private static final String DEFAULT_PASSWORD = "password";

    public DataSourcePasswordGuard(@Value("${spring.datasource.password:}") String password,
                                   Environment environment) {
        boolean prod = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (prod && DEFAULT_PASSWORD.equals(password)) {
            throw new IllegalStateException(
                    "Refusing to start in the 'prod' profile with the default database password. "
                    + "Set the DB_PASSWORD environment variable to the real credential.");
        }
    }
}
