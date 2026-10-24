package io.paradaux.hibernia.testsupport;

import org.junit.jupiter.api.Assertions;

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
 * Bidirectional audit of a plugin's {@code messages.properties} against its Java
 * source, catching the two ways i18n drifts silently:
 *
 * <ul>
 *   <li><b>used-but-undefined</b> — a message key passed to the framework i18n API
 *       ({@code message.send/format/component/componentOr/broadcast/has(...)}) in
 *       source whose namespace matches one of the plugin's key prefixes, but which
 *       is <em>absent</em> from {@code messages.properties}: at runtime that renders
 *       as a raw missing-key placeholder. Only i18n call sites are scanned, so
 *       same-namespace {@code @Permission}/{@code @Command}/{@code @Route} literals
 *       (Bukkit permission nodes / command routes) are not mistaken for keys.</li>
 *   <li><b>defined-but-unused</b> — a key <em>defined</em> in {@code messages.properties}
 *       (after removing the allowlisted framework-consumed prefixes) that appears in
 *       no source file at all: dead i18n that should be removed.</li>
 * </ul>
 *
 * <p>Plugin-agnostic: the caller supplies its source root, its properties file, its
 * key-namespace prefixes (e.g. {@code "treasury."}) and an allowlist of prefixes that
 * are defined-in-properties-but-consumed-by-the-framework (e.g. {@code "placeholder."},
 * {@code "hibernia."}) so those are not flagged as dead.</p>
 */
public final class MessageKeyAudit {

    /** Framework i18n methods that take a message key as their first {@code String} argument. */
    private static final Pattern I18N_CALL = Pattern.compile(
            "\\.(?:send|format|component|componentOr|broadcast|has)\\s*\\(");

    /** A double-quoted string literal (no escaped quotes inside — message keys never contain them). */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"\\\\]+)\"");

    private MessageKeyAudit() {
    }

    /**
     * Run the bidirectional audit and fail (via JUnit assertions) on any drift.
     *
     * @param sourceRoot        the plugin's main Java source root (e.g. {@code src/main/java}).
     * @param messagesFile      the plugin's {@code messages.properties}.
     * @param keyPrefixes       the plugin's own key namespaces (e.g. {@code "treasury.",
     *                          "find.", "shop."}). A used literal is treated as a message key
     *                          only when it starts with one of these <em>and</em> sits on an
     *                          i18n call line.
     * @param allowlistPrefixes prefixes defined in the properties but consumed by the framework
     *                          / templating rather than by plugin source (e.g. {@code
     *                          "placeholder.", "hibernia."}); keys under these are exempt from
     *                          the defined-but-unused check.
     */
    public static void assertBidirectional(Path sourceRoot,
                                           Path messagesFile,
                                           List<String> keyPrefixes,
                                           List<String> allowlistPrefixes) {
        List<Path> sources = javaSources(sourceRoot);
        String allSource = readAll(sources);
        Set<String> definedKeys = definedKeys(messagesFile);
        Set<String> usedAsMessageKey = usedMessageKeys(sources, keyPrefixes);

        // (a) used-but-undefined: message-key usages with no properties entry.
        List<String> undefined = usedAsMessageKey.stream()
                .filter(k -> !definedKeys.contains(k))
                .sorted()
                .collect(Collectors.toList());

        // (b) defined-but-unused: defined keys (minus allowlist) that appear in no source file.
        List<String> unused = new ArrayList<>();
        for (String key : definedKeys) {
            if (startsWithAny(key, allowlistPrefixes)) {
                continue;
            }
            if (!allSource.contains('"' + key + '"')) {
                unused.add(key);
            }
        }
        unused.sort(String::compareTo);

        StringBuilder failure = new StringBuilder();
        if (!undefined.isEmpty()) {
            failure.append("Message keys used in source but MISSING from ")
                    .append(messagesFile.getFileName()).append(" (they render as a raw missing-key ")
                    .append("placeholder at runtime):\n");
            undefined.forEach(k -> failure.append("  - ").append(k).append('\n'));
        }
        if (!unused.isEmpty()) {
            failure.append("Message keys DEFINED in ").append(messagesFile.getFileName())
                    .append(" but used in no source file (dead i18n — remove them, or allowlist the ")
                    .append("prefix if the framework consumes them):\n");
            unused.forEach(k -> failure.append("  - ").append(k).append('\n'));
        }

        Assertions.assertTrue(failure.isEmpty(), failure.toString());
    }

    // ---- internals ----

    private static Set<String> definedKeys(Path messagesFile) {
        // Parse with java.util.Properties so line-continuations (a trailing '\'),
        // comments and '='/':' separators are handled exactly as the runtime loader does —
        // hand-splitting on the first ':' mis-reads continuation lines of MiniMessage values
        // (which contain ':') as spurious keys.
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(messagesFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read messages file: " + messagesFile, e);
        }
        Set<String> keys = new LinkedHashSet<>();
        for (String name : properties.stringPropertyNames()) {
            keys.add(name);
        }
        return keys;
    }

    /** Every message-key literal used on an i18n call line, restricted to the plugin's prefixes. */
    private static Set<String> usedMessageKeys(List<Path> sources, List<String> keyPrefixes) {
        Set<String> used = new LinkedHashSet<>();
        for (Path src : sources) {
            List<String> lines;
            try {
                lines = Files.readAllLines(src, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read source file: " + src, e);
            }
            for (String line : lines) {
                if (!I18N_CALL.matcher(line).find()) {
                    continue;
                }
                Matcher m = STRING_LITERAL.matcher(line);
                while (m.find()) {
                    String literal = m.group(1);
                    if (startsWithAny(literal, keyPrefixes)) {
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

    private static boolean startsWithAny(String value, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
