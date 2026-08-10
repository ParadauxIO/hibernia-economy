package io.paradaux.chestshop.utils;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.assertThat;

class ColoursTest {

    @Test
    void constants_areTheExpectedSectionCodes() {
        assertThat(Colours.SECTION_CHAR).isEqualTo('§');
        assertThat(Colours.RED).isEqualTo("§c");
        assertThat(Colours.GREEN).isEqualTo("§a");
        assertThat(Colours.DARK_GREEN).isEqualTo("§2");
        assertThat(Colours.AQUA).isEqualTo("§b");
        assertThat(Colours.YELLOW).isEqualTo("§e");
        assertThat(Colours.GRAY).isEqualTo("§7");
        assertThat(Colours.DARK_GRAY).isEqualTo("§8");
        assertThat(Colours.BOLD).isEqualTo("§l");
    }

    @Test
    void isUtilityClass_privateConstructor() throws Exception {
        Constructor<Colours> ctor = Colours.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        assertThat(ctor.newInstance()).isNotNull();
    }
}
