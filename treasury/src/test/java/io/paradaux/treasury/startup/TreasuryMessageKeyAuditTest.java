package io.paradaux.treasury.startup;

import io.paradaux.hibernia.testsupport.MessageKeyAudit;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

/**
 * Bidirectional {@code messages.properties} ↔ source audit for Treasury
 * (treasury/testing/0002, treasury/testing/0005): every {@code treasury.*} key sent
 * through the i18n API must be defined, and every defined key (outside the framework-
 * consumed {@code placeholder.} namespace) must be used in source — no dead i18n.
 */
class TreasuryMessageKeyAuditTest {

    @Test
    void messagesAndSourceAgree() {
        Path moduleRoot = Path.of(System.getProperty("user.dir"));
        MessageKeyAudit.assertBidirectional(
                moduleRoot.resolve("src/main/java"),
                moduleRoot.resolve("src/main/resources/messages.properties"),
                // Treasury's own key namespace.
                List.of("treasury."),
                // Framework-/template-consumed prefixes, exempt from the dead-key check:
                // placeholder.* are MiniMessage tokens expanded into other messages, not
                // sent as keys themselves.
                List.of("placeholder."));
    }
}
