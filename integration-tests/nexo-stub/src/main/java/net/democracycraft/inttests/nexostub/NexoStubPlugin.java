package net.democracycraft.inttests.nexostub;

import com.nexomc.nexo.api.NexoItems;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Test stand-in for the Nexo plugin (registered under the name "Nexo" so ChestShop's
 * softdepend hook fires and calls {@code ItemService.enableNexo()}). Registers a couple
 * of defined custom items in {@link NexoItems}, and exposes RCON-drivable commands so the
 * harness can stock a shop chest with a Nexo item and count Nexo items in an inventory
 * by their {@code nexo:id} — validating ChestShop's native Nexo integration (PAR-291).
 */
public class NexoStubPlugin extends JavaPlugin {

    private static final NamespacedKey NEXO_KEY = NamespacedKey.fromString("nexo:id");

    @Override
    public void onEnable() {
        register("test_ruby", Material.DIAMOND, "Test Ruby");
        register("test_gem", Material.EMERALD, "Test Gem");
        getLogger().info("Nexo (stub) enabled — registered test items: test_ruby, test_gem");
    }

    /** A "clean" Nexo item: base material + non-empty name + the nexo:id PDC (as real Nexo stamps). */
    private void register(String id, Material material, String name) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(Component.text(name));
            meta.getPersistentDataContainer().set(NEXO_KEY, PersistentDataType.STRING, id);
        });
        NexoItems.register(id, item);
    }

    private ItemStack itemOf(String id) {
        var builder = NexoItems.itemFromId(id);
        return builder == null ? null : builder.build();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase()) {
            case "nexostock":
                return doStock(sender, args);
            case "nexogive":
                return doGive(sender, args);
            case "nexocount":
                return doCount(sender, args);
            default:
                return false;
        }
    }

    /** /nexostock <world> <x> <y> <z> <id> <count> — put a Nexo item into the container there. */
    private boolean doStock(CommandSender sender, String[] args) {
        if (args.length != 6) { sender.sendMessage("usage: /nexostock <world> <x> <y> <z> <id> <count>"); return true; }
        World w = Bukkit.getWorld(args[0]);
        if (w == null) { sender.sendMessage("world not found: " + args[0]); return true; }
        int x, y, z, count;
        try {
            x = Integer.parseInt(args[1]); y = Integer.parseInt(args[2]); z = Integer.parseInt(args[3]);
            count = Integer.parseInt(args[5]);
        } catch (NumberFormatException e) { sender.sendMessage("coords/count must be ints"); return true; }
        ItemStack item = itemOf(args[4]);
        if (item == null) { sender.sendMessage("unknown nexo id: " + args[4]); return true; }
        Block block = w.getBlockAt(x, y, z);
        // getState(false) → the LIVE tile-entity state, so inventory changes persist without
        // update() (a snapshot state's inventory edits are silently dropped).
        if (!(block.getState(false) instanceof Container container)) { sender.sendMessage("not a container: " + block.getType()); return true; }
        item.setAmount(count);
        container.getInventory().addItem(item);
        sender.sendMessage("nexostock " + args[4] + " x" + count + " -> " + block.getType());
        return true;
    }

    /** /nexogive <player> <id> <count> — give a Nexo item to a player. */
    private boolean doGive(CommandSender sender, String[] args) {
        if (args.length != 3) { sender.sendMessage("usage: /nexogive <player> <id> <count>"); return true; }
        Player p = Bukkit.getPlayerExact(args[0]);
        if (p == null) { sender.sendMessage("player not found: " + args[0]); return true; }
        ItemStack item = itemOf(args[1]);
        if (item == null) { sender.sendMessage("unknown nexo id: " + args[1]); return true; }
        int count;
        try { count = Integer.parseInt(args[2]); } catch (NumberFormatException e) { sender.sendMessage("count must be int"); return true; }
        item.setAmount(count);
        p.getInventory().addItem(item);
        sender.sendMessage("nexogive " + args[1] + " x" + count + " -> " + p.getName());
        return true;
    }

    /** /nexocount <player> <id> — count items whose nexo:id equals id. Prints "nexocount <id> = <n>". */
    private boolean doCount(CommandSender sender, String[] args) {
        if (args.length != 2) { sender.sendMessage("usage: /nexocount <player> <id>"); return true; }
        Player p = Bukkit.getPlayerExact(args[0]);
        if (p == null) { sender.sendMessage("player not found: " + args[0]); return true; }
        int total = 0;
        for (ItemStack item : p.getInventory().getContents()) {
            if (item != null && args[1].equals(NexoItems.idFromItem(item))) {
                total += item.getAmount();
            }
        }
        sender.sendMessage("nexocount " + args[1] + " = " + total);
        return true;
    }
}
