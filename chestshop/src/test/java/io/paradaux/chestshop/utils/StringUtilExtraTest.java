package io.paradaux.chestshop.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Fills the null-input branch of {@link StringUtil} the primary test doesn't reach. */
class StringUtilExtraTest {

    @Test
    void stripColourCodes_returnsNullForNullInput() {
        assertThat(StringUtil.stripColourCodes((String) null)).isNull();
    }
}
