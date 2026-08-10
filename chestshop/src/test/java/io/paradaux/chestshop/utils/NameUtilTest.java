package io.paradaux.chestshop.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NameUtilTest {

    @Test
    void stripUsername_shorterThanLimit_isReturnedUnchanged() {
        assertThat(NameUtil.stripUsername("Notch")).isEqualTo("Notch");
    }

    @Test
    void stripUsername_longerThan15_isTruncatedToSignWidth() {
        assertThat(NameUtil.stripUsername("ThisNameIsWayTooLong")).isEqualTo("ThisNameIsWayTo");
        assertThat(NameUtil.stripUsername("ThisNameIsWayTooLong")).hasSize(15);
    }

    @Test
    void stripUsername_customLength_truncatesToThatLength() {
        assertThat(NameUtil.stripUsername("abcdefgh", 3)).isEqualTo("abc");
        assertThat(NameUtil.stripUsername("ab", 3)).isEqualTo("ab");
    }
}
