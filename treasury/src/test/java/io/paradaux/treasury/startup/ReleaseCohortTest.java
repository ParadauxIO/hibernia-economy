package io.paradaux.treasury.startup;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ReleaseCohortTest {
    @Test
    void acceptsOneCompleteRelease() {
        assertThatCode(() -> ReleaseCohort.verify("release-42", name -> "release-42"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingOrMixedPlugins() {
        for (String member : new String[] {"Treasury", "Business", "ChestShop", "TreasuryAPI"}) {
            assertThatThrownBy(() -> ReleaseCohort.verify("new", name -> name.equals(member) ? "old" : "new"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(member);
            assertThatThrownBy(() -> ReleaseCohort.verify("new", name -> name.equals(member) ? null : "new"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(member);
        }
    }
}
