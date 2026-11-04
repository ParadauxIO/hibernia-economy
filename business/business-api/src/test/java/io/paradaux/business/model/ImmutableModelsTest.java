package io.paradaux.business.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ImmutableModelsTest {
    @ParameterizedTest
    @ValueSource(
            classes = {
                Firm.class,
                FirmAccount.class,
                FirmEmployee.class,
                FirmPlayer.class,
                FirmRole.class,
                FirmRolePermission.class,
                FirmBalanceEntry.class
            })
    void publicModelsCannotBeMutatedOrSubclassed(Class<?> model) {
        assertThat(Modifier.isFinal(model.getModifiers())).isTrue();
        assertThat(model.getMethods()).noneMatch(method -> method.getName().startsWith("set"));
        Set<Class<?>> immutableTypes = Set.of(
                Integer.class,
                String.class,
                UUID.class,
                Boolean.class,
                LocalDateTime.class,
                RolePermission.class,
                BigDecimal.class);
        for (var field : model.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            assertThat(Modifier.isPrivate(field.getModifiers()))
                    .as(field.toString())
                    .isTrue();
            assertThat(Modifier.isFinal(field.getModifiers()))
                    .as(field.toString())
                    .isTrue();
            assertThat(immutableTypes).contains(field.getType());
        }
    }

    @Test
    void allPlayerIdentifiersAreTypedUuids() throws Exception {
        assertThat(Firm.class.getMethod("getProprietorUuid").getReturnType()).isEqualTo(UUID.class);
        for (String getter : new String[] {"getPlayerUuid", "getAddedBy", "getRemovedBy"}) {
            assertThat(FirmEmployee.class.getMethod(getter).getReturnType()).isEqualTo(UUID.class);
        }
        assertThat(FirmPlayer.class.getMethod("getPlayerUuid").getReturnType()).isEqualTo(UUID.class);
        UUID id = UUID.randomUUID();
        assertThat(new FirmPlayer(id, "Alice", "alice", null, null).getUniqueId())
                .isEqualTo(id);
        assertThat(new FirmPlayer(null, "Alice", "alice", null, null).getUniqueId())
                .isNull();
    }
}
