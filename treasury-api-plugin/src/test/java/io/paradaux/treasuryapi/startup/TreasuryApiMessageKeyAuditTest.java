package io.paradaux.treasuryapi.startup;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Bidirectional {@code messages.properties} ↔ source audit for TreasuryAPI
 * (treasury-api-plugin/testing): every {@code treasuryapi.*} key referenced in
 * source must be defined, and every defined key (outside the framework-consumed
 * {@code placeholder.} namespace) must be referenced — no dead i18n.
 *
 * <p>Unlike the shared {@code io.paradaux.hibernia.testsupport.MessageKeyAudit},
 * this plugin also routes some player-facing keys through the framework's semantic
 * exceptions (e.g. {@code throw new NotFoundException("treasuryapi.key.not-found")}),
 * which the command dispatcher's {@code ErrorRenderer} resolves against the bundle.
 * Those throw-sites are a legitimate second class of key <em>usage</em>, so the
 * scanner recognises both the i18n call surface and the framework
 * {@code *Exception(...)} constructors — otherwise a genuinely-used exception key
 * would be mis-reported as dead.
 */
class TreasuryApiMessageKeyAuditTest {

    private static final String KEY_PREFIX = "treasuryapi.";
    private static final String ALLOWLIST_PREFIX = "placeholder.";

    /** Framework i18n methods that take a message key as their first {@code String} argument. */
    private static final Pattern I18N_CALL = Pattern.compile(
            "\\.(?:send|format|component|componentOr|broadcast|has)\\s*\\(");

    /**
     * Framework semantic-exception constructors whose first {@code String} argument
     * doubles as a message key (see {@code KeyedException}) — NotFound / BadCommand /
     * NoPermission / Conflict / ExceedsLimit.
     */
    private static final Pattern KEYED_EXCEPTION = Pattern.compile(
            "new\\s+(?:NotFound|BadCommand|NoPermission|Conflict|ExceedsLimit)Exception\\s*\\(");

    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"\\\\]+)\"");

    @Test
    void messagesAndSourceAgree() {
        Path moduleRoot = Path.of(System.getProperty("user.dir"));
        Path sourceRoot = moduleRoot.resolve("src/main/java");
        Path messagesFile = moduleRoot.resolve("src/main/resources/messages.properties");

        List<Path> sources = javaSources(sourceRoot);
        String allSource = readAll(sources);
        Set<String> definedKeys = definedKeys(messagesFile);
        Set<String> usedKeys = usedKeys(sources);

        List<String> undefined = usedKeys.stream()
                .filter(k -> !definedKeys.contains(k))
                .sorted()
                .collect(Collectors.toList());

        List<String> unused = new ArrayList<>();
        for (String key : definedKeys) {
            if (key.startsWith(ALLOWLIST_PREFIX)) {
                continue;
            }
            if (!allSource.contains('"' + key + '"')) {
                unused.add(key);
            }
        }
        unused.sort(String::compareTo);

        StringBuilder failure = new StringBuilder();
        if (!undefined.isEmpty()) {
            failure.append("Message keys used in source but MISSING from messages.properties ")
                    .append("(they render as a raw missing-key placeholder at runtime):\n");
            undefined.forEach(k -> failure.append("  - ").append(k).append('\n'));
        }
        if (!unused.isEmpty()) {
            failure.append("Message keys DEFINED in messages.properties but referenced in no source ")
                    .append("file (dead i18n — remove them, or allowlist the prefix):\n");
            unused.forEach(k -> failure.append("  - ").append(k).append('\n'));
        }
        Assertions.assertTrue(failure.isEmpty(), failure.toString());
    }

    private static Set<String> definedKeys(Path messagesFile) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(messagesFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read messages file: " + messagesFile, e);
        }
        return new LinkedHashSet<>(properties.stringPropertyNames());
    }

    /** Message-key literals used on an i18n call line OR a framework keyed-exception constructor line. */
    private static Set<String> usedKeys(List<Path> sources) {
        Set<String> used = new LinkedHashSet<>();
        for (Path src : sources) {
            List<String> lines;
            try {
                lines = Files.readAllLines(src, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read source file: " + src, e);
            }
            for (String line : lines) {
                if (!I18N_CALL.matcher(line).find() && !KEYED_EXCEPTION.matcher(line).find()) {
                    continue;
                }
                Matcher m = STRING_LITERAL.matcher(line);
                while (m.find()) {
                    String literal = m.group(1);
                    if (literal.startsWith(KEY_PREFIX)) {
                        used.add(literal);
                    }
                }
            }
        }
        return used;
    }

    private static List<Path> javaSources(Path sourceRoot) {
        Assertions.assertTrue(Files.isDirectory(sourceRoot),
                "Source root does not exist or is not a directory: " + sourceRoot);
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot walk source root: " + sourceRoot, e);
        }
    }

    private static String readAll(List<Path> sources) {
        StringBuilder sb = new StringBuilder();
        for (Path src : sources) {
            try {
                sb.append(Files.readString(src, StandardCharsets.UTF_8)).append('\n');
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read source file: " + src, e);
            }
        }
        return sb.toString();
    }
}
