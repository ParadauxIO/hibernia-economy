package io.paradaux.chestshop.model;

import io.paradaux.chestshop.support.ServerTest;
import io.paradaux.hibernia.framework.i18n.Message;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the {@link ItemInfoLines} accumulator: keyed rendering from a message key, raw
 * component / legacy-string lines, duplicate-key collapse and insertion order. Uses a real
 * MockBukkit CommandSender + ItemStack; the framework {@link Message} (an external boundary)
 * is mocked.
 */
class ItemInfoLinesTest extends ServerTest {

    private Message message;
    private CommandSender sender;
    private ItemStack item;

    @BeforeEach
    void wire() {
        message = mock(Message.class);
        sender = player("Steve");
        item = item(Material.DIAMOND, 1);
    }

    @Test
    void exposesSenderAndItem() {
        ItemInfoLines lines = new ItemInfoLines(sender, item, message);
        assertThat(lines.getSender()).isSameAs(sender);
        assertThat(lines.getItem()).isSameAs(item);
        assertThat(lines.getMessages()).isEmpty();
    }

    @Test
    void addMessage_rendersViaFramework_andStoresUnderKey() {
        Component rendered = Component.text("rendered");
        when(message.component(anyString(), anyMap())).thenReturn(rendered);

        ItemInfoLines lines = new ItemInfoLines(sender, item, message);
        lines.addMessage("chestshop.iteminfo_repaircost", "cost", "5");

        assertThat(lines.getMessages()).hasSize(1);
        Map.Entry<String, Component> entry = lines.getMessages().iterator().next();
        assertThat(entry.getKey()).isEqualTo("chestshop.iteminfo_repaircost");
        assertThat(entry.getValue()).isEqualTo(rendered);
    }

    @Test
    void addRawMessage_component_isStoredVerbatim() {
        Component raw = Component.text("raw");
        ItemInfoLines lines = new ItemInfoLines(sender, item, message);
        lines.addRawMessage("lore", raw);

        assertThat(lines.getMessages().iterator().next().getValue()).isEqualTo(raw);
    }

    @Test
    void addRawMessage_legacyString_isDeserialized() {
        ItemInfoLines lines = new ItemInfoLines(sender, item, message);
        lines.addRawMessage("lore", "§aGreen");

        Component expected = LegacyComponentSerializer.legacySection().deserialize("§aGreen");
        assertThat(lines.getMessages().iterator().next().getValue()).isEqualTo(expected);
    }

    @Test
    void duplicateKey_collapses_andPreservesInsertionOrder() {
        Component first = Component.text("first");
        Component second = Component.text("second");

        ItemInfoLines lines = new ItemInfoLines(sender, item, message);
        lines.addRawMessage("a", first);
        lines.addRawMessage("b", Component.text("b"));
        lines.addRawMessage("a", second); // overrides "a", keeps position

        List<Map.Entry<String, Component>> entries = List.copyOf(lines.getMessages());
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).getKey()).isEqualTo("a");
        assertThat(entries.get(0).getValue()).isEqualTo(second);
        assertThat(entries.get(1).getKey()).isEqualTo("b");
    }
}
