package io.paradaux.chestshop.support;

import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds a real {@link ChestShopConfiguration} populated with the {@code @ConfigurationValue}
 * defaults — exactly what HiberniaFramework's Configurator applies at load — so tests exercise
 * the real config getters (including the hand-written adapter getters) rather than a mock.
 */
public final class TestConfigs {

    private TestConfigs() {
    }

    /** A config with every field set to its declared default. */
    public static ChestShopConfiguration defaults() {
        ChestShopConfiguration c = new ChestShopConfiguration();
        for (Field f : ChestShopConfiguration.class.getDeclaredFields()) {
            ConfigurationValue cv = f.getAnnotation(ConfigurationValue.class);
            if (cv == null) {
                continue;
            }
            f.setAccessible(true);
            try {
                f.set(c, coerce(cv.defaultValue(), f.getType()));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Could not set " + f.getName(), e);
            }
        }
        return c;
    }

    /** Override a single field on a config (by field name), for a test that needs a non-default. */
    public static ChestShopConfiguration with(ChestShopConfiguration c, String field, Object value) {
        try {
            Field f = ChestShopConfiguration.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(c, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("No such config field: " + field, e);
        }
        return c;
    }

    private static Object coerce(String v, Class<?> type) {
        if (type == boolean.class) {
            return Boolean.parseBoolean(v);
        }
        if (type == int.class) {
            return Integer.parseInt(v);
        }
        if (type == long.class) {
            return Long.parseLong(v);
        }
        if (type == double.class) {
            return Double.parseDouble(v);
        }
        if (type == BigDecimal.class) {
            return new BigDecimal(v);
        }
        if (type == List.class) {
            return v.isEmpty() ? List.of()
                    : Arrays.stream(v.split(",")).map(String::trim).collect(Collectors.toList());
        }
        return v; // String
    }
}
