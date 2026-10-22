package net.democracycraft.inttests.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GameTimeResponseTest {
    @Test
    void readsMinecraft263TimeResponseWithTickUnits() {
        assertThat(Dialogs.parseGameTime("The game time is 9317 tick(s)")).isEqualTo(9317L);
        assertThat(Dialogs.parseGameTime("The game time is 0 tick(s)")).isZero();
    }

    @Test
    void retainsOlderTimeResponseSupport() {
        assertThat(Dialogs.parseGameTime("The time is 42")).isEqualTo(42L);
    }

    @Test
    void rejectsMissingOrOutOfRangeTimeInsteadOfAcceptingAnErrorReply() {
        assertThatThrownBy(() -> Dialogs.parseGameTime("The game time is -1 tick(s)"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Dialogs.parseGameTime("Unknown command"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Dialogs.parseGameTime("The game time is 9223372036854775808 tick(s)"))
                .isInstanceOf(IllegalStateException.class);
    }
}
