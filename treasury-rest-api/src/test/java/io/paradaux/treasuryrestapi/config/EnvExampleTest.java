package io.paradaux.treasuryrestapi.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code .env.example} is the only place a new deployment learns which variables
 * exist. Every {@code ${NAME…}} placeholder in the application YAMLs must appear
 * there (uncommented if required, commented with its default if optional), so the
 * file cannot drift from the configuration.
 */
class EnvExampleTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)[:}]");
    private static final Pattern DOCUMENTED = Pattern.compile("^#?\\s?([A-Z][A-Z0-9_]*)=", Pattern.MULTILINE);

    @Test
    void documentsEveryVariableTheApplicationYamlsRead() throws IOException {
        Set<String> read = new TreeSet<>();
        try (var yamls = Files.newDirectoryStream(Path.of("src/main/resources"), "application*.yaml")) {
            for (Path yaml : yamls) {
                Matcher m = PLACEHOLDER.matcher(Files.readString(yaml));
                while (m.find()) {
                    // The uat profile reads the same variables under a UAT_ prefix.
                    read.add(m.group(1).replaceFirst("^UAT_", ""));
                }
            }
        }

        Set<String> documented = new TreeSet<>();
        Matcher m = DOCUMENTED.matcher(Files.readString(Path.of(".env.example")));
        while (m.find()) {
            documented.add(m.group(1));
        }

        assertThat(read).isNotEmpty();
        assertThat(documented)
                .as("add the missing variables to treasury-rest-api/.env.example")
                .containsAll(read);
    }
}
