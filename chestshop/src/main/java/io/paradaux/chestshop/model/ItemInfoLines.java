package io.paradaux.chestshop.model;

import io.paradaux.chestshop.utils.MessageUtil;
import io.paradaux.chestshop.ChestShop;
import io.paradaux.hibernia.framework.i18n.Message;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ordered accumulator for the lines {@link InfoService#collectItemInfo} produces for
 * an {@code /iteminfo} call. Lines are pre-rendered (prefix-less) {@link Component}s
 * keyed by a stable string so duplicates collapse (a later contributor can override an
 * earlier one by reusing its key) and insertion order is preserved; the caller sends
 * them in order. Lines come either from a {@code messages.properties} key (rendered via
 * the framework {@link io.paradaux.hibernia.framework.i18n.Message}) or as a raw
 * component / legacy-section string.
 *
 * <p>Was the {@code ItemInfoEvent} carrier of the old in-process event bus (PAR-282);
 * it is plain data now, owned by the info service.
 *
 * @author Acrobot
 */
public final class ItemInfoLines {

    private final CommandSender sender;
    private final ItemStack item;
    private final Message message;
    private final Map<String, Component> messages = new LinkedHashMap<>();

    public ItemInfoLines(CommandSender sender, ItemStack item, Message message) {
        this.sender = sender;
        this.item = item;
        this.message = message;
    }

    /** @return the CommandSender who initiated the {@code /iteminfo} call. */
    public CommandSender getSender() {
        return sender;
    }

    /** @return the item being described. */
    public ItemStack getItem() {
        return item;
    }

    /**
     * Add a line rendered from a {@code messages.properties} key (prefix blanked),
     * stored under that key.
     *
     * @param key  the message key (e.g. {@code chestshop.iteminfo_repaircost})
     * @param args alternating placeholder name/value pairs
     */
    public void addMessage(String key, String... args) {
        messages.put(key, render(key, args));
    }

    private Component render(String key, String... args) {
        return message.component(key, MessageUtil.values(false, null, args));
    }

    public void addRawMessage(String key, Component message) {
        messages.put(key, message);
    }

    public void addRawMessage(String key, String message) {
        // Raw item-info lines (lore/enchantments/potions) carry §-section legacy
        // colour codes (ChatColor.*), so deserialize them natively.
        messages.put(key, LegacyComponentSerializer.legacySection().deserialize(message));
    }

    /** @return the accumulated lines as ordered key → pre-rendered {@link Component} entries. */
    public Collection<Map.Entry<String, Component>> getMessages() {
        return messages.entrySet();
    }
}
