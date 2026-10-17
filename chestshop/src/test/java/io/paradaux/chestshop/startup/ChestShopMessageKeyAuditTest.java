package io.paradaux.chestshop.startup;

import io.paradaux.hibernia.testsupport.MessageKeyAudit;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

/**
 * Bidirectional {@code messages.properties} ↔ source audit for ChestShop
 * (chestshop/testing/0002): every message key sent through the i18n API under one of
 * ChestShop's namespaces must be defined, and every defined key (outside the framework-
 * consumed prefixes) must be used in source — no dead i18n.
 */
class ChestShopMessageKeyAuditTest {

    @Test
    void messagesAndSourceAgree() {
        Path moduleRoot = Path.of(System.getProperty("user.dir"));
        MessageKeyAudit.assertBidirectional(
                moduleRoot.resolve("src/main/java"),
                moduleRoot.resolve("src/main/resources/messages.properties"),
                // ChestShop's own key namespaces: core messages, the /find flow, and the
                // shop.* sign/status strings.
                List.of("chestshop.", "find.", "shop."),
                // Framework-/template-consumed prefixes, exempt from the dead-key check:
                //  - placeholder.*        : MiniMessage tokens expanded into other messages.
                //  - hibernia.*           : the framework's own error keys, consulted by the
                //                           commander's ErrorRenderer, never by plugin source.
                //  - chestshop.prefix     : the {prefix} template token expanded into other
                //                           messages (the chestshop analogue of placeholder.prefix),
                //                           not sent as a key itself.
                List.of("placeholder.", "hibernia.", "chestshop.prefix"));
    }
}
