package io.paradaux.chestshop.utils;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MessageUtilTest {

    @Test
    void withPrefix_doesNotBlankThePrefix() {
        Map<String, Object> v = MessageUtil.values(true, null);
        assertThat(v).doesNotContainKey("prefix");
    }

    @Test
    void withoutPrefix_blanksThePrefix() {
        Map<String, Object> v = MessageUtil.values(false, null);
        assertThat(v).containsEntry("prefix", "");
    }

    @Test
    void mergesBaseMap() {
        Map<String, Object> v = MessageUtil.values(true, Map.of("amount", "5"));
        assertThat(v).containsEntry("amount", "5");
    }

    @Test
    void appliesReplacementPairs_andIgnoresATrailingUnpairedValue() {
        Map<String, Object> v = MessageUtil.values(true, null, "a", "1", "b", "2", "c");
        assertThat(v).containsEntry("a", "1").containsEntry("b", "2");
        assertThat(v).doesNotContainKey("c"); // unpaired trailing value is ignored
    }

    @Test
    void isUtilityClass_privateConstructor() throws Exception {
        Constructor<MessageUtil> ctor = MessageUtil.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        assertThat(ctor.newInstance()).isNotNull();
    }
}
