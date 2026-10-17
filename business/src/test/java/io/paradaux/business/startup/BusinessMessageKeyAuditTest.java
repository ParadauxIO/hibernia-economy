package io.paradaux.business.startup;

import io.paradaux.hibernia.testsupport.MessageKeyAudit;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

/**
 * Bidirectional {@code messages.properties} ↔ source audit for Business
 * (business/testing/0002): every {@code business.*} key sent through the i18n API must
 * be defined, and every defined key (outside the framework-consumed {@code placeholder.}
 * namespace) must be used in source — no dead i18n.
 */
class BusinessMessageKeyAuditTest {

    @Test
    void messagesAndSourceAgree() {
        Path moduleRoot = Path.of(System.getProperty("user.dir"));
        MessageKeyAudit.assertBidirectional(
                moduleRoot.resolve("src/main/java"),
                moduleRoot.resolve("src/main/resources/messages.properties"),
                List.of("business."),
                List.of("placeholder."));
    }
}
