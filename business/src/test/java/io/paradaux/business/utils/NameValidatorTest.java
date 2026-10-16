package io.paradaux.business.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NameValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"Banned", "BANNED Trading", "Ban.n-ed", "B a n n e d", "ShopBannedCo", "Ban_ned"})
    void blocksLiteralTermsIgnoringCaseAndSeparators(String name) {
        assertThat(NameValidator.containsBlockedTerm(name, List.of("banned"))).isTrue();
    }

    @Test
    void handlesPhrasesAndEmptyListsWithoutRegexInterpretation() {
        assertThat(NameValidator.containsBlockedTerm("Bad_Phrase Inc", List.of("bad phrase"))).isTrue();
        assertThat(NameValidator.containsBlockedTerm("Acme", List.of())).isFalse();
        assertThat(NameValidator.containsBlockedTerm("Acme", List.of("", " ._-", "a.*"))).isFalse();
        assertThat(NameValidator.containsBlockedTerm("Acme", List.of("banned"))).isFalse();
    }

    @Test
    void firmName_acceptsCommonCases() {
        assertThat(NameValidator.isValidFirmName("Acme")).isTrue();
        assertThat(NameValidator.isValidFirmName("Acme Inc.")).isTrue();
        assertThat(NameValidator.isValidFirmName("My_Firm-2")).isTrue();
    }

    @Test
    void firmName_rejectsMiniMessageInjection() {
        assertThat(NameValidator.isValidFirmName("<click>Hack</click>")).isFalse();
        assertThat(NameValidator.isValidFirmName("Bad<thing>")).isFalse();
        assertThat(NameValidator.isValidFirmName("End>tag")).isFalse();
    }

    @Test
    void firmName_rejectsAmpersand() { // PAR-53: & is the legacy colour-code char
        assertThat(NameValidator.isValidFirmName("AT&T")).isFalse();
        assertThat(NameValidator.isValidFirmName("Red&cFirm")).isFalse();
        assertThat(NameValidator.isValidAccountName("Acc&ount")).isFalse();
    }

    @Test
    void firmName_rejectsControlCharsAndOdd() {
        assertThat(NameValidator.isValidFirmName("name\nwith\nnewline")).isFalse();
        assertThat(NameValidator.isValidFirmName("colon:here")).isFalse();
        assertThat(NameValidator.isValidFirmName("emoji😀")).isFalse();
    }

    @Test
    void firmName_lengthBounds() {
        assertThat(NameValidator.isValidFirmName("a")).isFalse();              // too short
        assertThat(NameValidator.isValidFirmName("ab")).isTrue();              // min
        assertThat(NameValidator.isValidFirmName("x".repeat(32))).isTrue();    // max
        assertThat(NameValidator.isValidFirmName("x".repeat(33))).isFalse();   // over max
    }

    @Test
    void firmName_rejectsNull() {
        assertThat(NameValidator.isValidFirmName(null)).isFalse();
    }

    @Test
    void accountName_lengthBounds() {
        assertThat(NameValidator.isValidAccountName("a")).isFalse();
        assertThat(NameValidator.isValidAccountName("x".repeat(40))).isTrue();
        assertThat(NameValidator.isValidAccountName("x".repeat(41))).isFalse();
    }

    @Test
    void accountName_rejectsNull() {
        assertThat(NameValidator.isValidAccountName(null)).isFalse();
    }
}
