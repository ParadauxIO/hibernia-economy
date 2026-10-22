package io.paradaux.treasury.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasury.model.economy.TransactionEntry;
import io.paradaux.treasury.services.AccountService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class TransactionEntryRendererTest {
    @TempDir
    Path directory;

    @Test
    void rendersSignedAmountsWithColorAndKeepsMemoLiteral() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getResource(anyString()))
                .thenAnswer(inv -> getClass().getClassLoader().getResourceAsStream(inv.getArgument(0, String.class)));
        doAnswer(inv -> null).when(plugin).saveResource(anyString(), anyBoolean());
        try (var input = getClass().getClassLoader().getResourceAsStream("messages.properties")) {
            Files.copy(input, directory.resolve("messages.properties"));
        }
        Message message = new Message(plugin);
        AccountService accounts = mock(AccountService.class);
        when(accounts.formatAmount(new BigDecimal("15.00"))).thenReturn("$15.00");
        for (int sign : new int[] {1, -1}) {
            CommandSender recipient = mock(CommandSender.class);
            TransactionEntry entry = new TransactionEntry();
            entry.setTxnId(42);
            entry.setAmount(new BigDecimal("15.00").multiply(BigDecimal.valueOf(sign)));
            entry.setMemo("<red>memo</red> {amount}");
            TransactionEntryRenderer.send(recipient, message, accounts, entry);
            ArgumentCaptor<Component> output = ArgumentCaptor.forClass(Component.class);
            verify(recipient).sendMessage(output.capture());
            String signed = (sign > 0 ? "+" : "-") + "$15.00";
            assertThat(PlainTextComponentSerializer.plainText().serialize(output.getValue()))
                    .isEqualTo("#42 " + signed + " - <red>memo</red> {amount} (—)");
            assertThat(hasColoredText(output.getValue(), signed, sign > 0 ? NamedTextColor.GREEN : NamedTextColor.RED))
                    .isTrue();
        }
    }

    private static boolean hasColoredText(Component component, String text, NamedTextColor color) {
        return (PlainTextComponentSerializer.plainText().serialize(component).equals(text)
                        && color.equals(component.color()))
                || component.children().stream().anyMatch(child -> hasColoredText(child, text, color));
    }
}
