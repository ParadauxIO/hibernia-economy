package io.paradaux.business.utils;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SuggestionsTest {

    @Test
    void match_filtersCaseInsensitivePrefix() {
        List<String> out = Suggestions.match(List.of("Acme", "Apex", "Globex"), "a", 20);
        assertThat(out).containsExactly("Acme", "Apex");
    }

    @Test
    void match_nullPrefixReturnsAllSortedCaseInsensitive() {
        List<String> out = Suggestions.match(List.of("globex", "Acme", "apex"), null, 20);
        assertThat(out).containsExactly("Acme", "apex", "globex");
    }

    @Test
    void match_distinctAndCapped() {
        List<String> out = Suggestions.match(List.of("Acme", "Acme", "Apex", "Anvil"), "a", 2);
        assertThat(out).containsExactly("Acme", "Anvil");
    }

    @Test
    void match_emptyPoolIsEmpty() {
        assertThat(Suggestions.match(Set.of(), "x", 20)).isEmpty();
    }
}
