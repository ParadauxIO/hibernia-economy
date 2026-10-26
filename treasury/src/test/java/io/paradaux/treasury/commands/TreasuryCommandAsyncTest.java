package io.paradaux.treasury.commands;

import io.paradaux.hibernia.framework.commander.annotations.Async;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the fix for ADT-18 part 2: {@code /treasury admin balance} and
 * {@code /treasury admin info} must run {@code @Async} — adminInfo does ~5
 * sequential DB round-trips and must never execute on the Bukkit main thread.
 */
class TreasuryCommandAsyncTest {

    @Test
    void adminBalance_isAsync() throws NoSuchMethodException {
        Method m = TreasuryCommand.class.getMethod("adminBalance",
                CommandSender.class, String.class, String.class);
        assertThat(m.isAnnotationPresent(Async.class)).isTrue();
    }

    @Test
    void adminInfo_isAsync() throws NoSuchMethodException {
        Method m = TreasuryCommand.class.getMethod("adminInfo",
                CommandSender.class, String.class, String.class);
        assertThat(m.isAnnotationPresent(Async.class)).isTrue();
    }
}
