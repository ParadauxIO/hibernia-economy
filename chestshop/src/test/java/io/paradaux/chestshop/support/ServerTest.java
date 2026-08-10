package io.paradaux.chestshop.support;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Base for tests that need a live in-memory Bukkit server (MockBukkit) so real ItemStacks,
 * inventories, blocks and players behave for real — rather than being mock-verified.
 */
public abstract class ServerTest {

    protected ServerMock server;

    @BeforeEach
    protected void bootServer() {
        server = MockBukkit.mock();
    }

    @AfterEach
    protected void shutdownServer() {
        MockBukkit.unmock();
    }

    /** A real ItemStack of the given material/amount. */
    protected ItemStack item(Material material, int amount) {
        return new ItemStack(material, amount);
    }

    /** A real, empty double-chest-sized inventory (no holder). */
    protected Inventory chest(int slots) {
        return server.createInventory(null, slots);
    }

    /** A real chest inventory pre-filled with the given stacks. */
    protected Inventory chestWith(int slots, ItemStack... contents) {
        Inventory inv = server.createInventory(null, slots);
        inv.addItem(contents);
        return inv;
    }

    /** A registered player with the given name. */
    protected Player player(String name) {
        return server.addPlayer(name);
    }
}
