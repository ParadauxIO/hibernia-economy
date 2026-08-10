package io.paradaux.chestshop.utils.encoding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Error paths + constructor for {@link Base62} the primary parameterised test doesn't reach. */
class Base62ExtraTest {

    @Test
    void constructor_isInstantiable() {
        assertThat(new Base62()).isNotNull();
    }

    @Test
    void encode_rejectsNegativeNumbers() {
        assertThatThrownBy(() -> Base62.encode(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void decode_rejectsAnInvalidCharacter() {
        assertThatThrownBy(() -> Base62.decode("12!45"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Base62 character");
    }
}
