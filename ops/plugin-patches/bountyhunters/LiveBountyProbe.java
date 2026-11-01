import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import net.Indyuce.bountyhunters.common.gui.Navigator;
import net.Indyuce.bountyhunters.common.gui.editable.*;
import net.Indyuce.bountyhunters.common.gui.editable.item.*;
import net.Indyuce.bountyhunters.common.gui.editable.item.builtin.*;
import net.Indyuce.bountyhunters.common.util.*;

/** Uses real loaded BountyHunters, Bukkit inventories, and the configured parser. No player writes. */
public class LiveBountyProbe {
  static final class Definition extends EditableInventory {
    Definition() { super("staging-probe"); }
    @Override public String getName() { return "Staging probe"; }
    @Override public int getVanillaSlots() { return 9; }
    @Override public InventoryItem<?> resolveItem(String name, ConfigurationSection config) { throw new UnsupportedOperationException(); }
  }
  static final class View extends GeneratedInventory<AbstractPlayerData> {
    int count;
    View(Navigator<AbstractPlayerData> navigator, Definition definition) { super(navigator, definition); enablePagination(10); }
    @Override public int getMaxPage() { return computeMaxPage(count); }
    @Override public void open() { getInventory(); } // Render only; never open a player's inventory.
  }
  static ConfigurationSection config(String name, int slot) {
    ConfigurationSection c = new YamlConfiguration().createSection(name);
    c.set("item", "ARROW"); c.set("slots", List.of(Integer.toString(slot)));
    c.set("name", "&a" + name); c.set("hide_if_no_page", false);
    return c;
  }
  public static String run(Plugin actual) {
    if (!(actual instanceof AbstractPlugin plugin) || !actual.isEnabled()) throw new AssertionError("BountyHunters unavailable");
    AbstractPlayerData data = new AbstractPlayerData() {
      public Player getPlayer() { return null; }
      public boolean isOnline() { return false; }
    };
    Definition definition = new Definition();
    NextPageItem next = new NextPageItem(config("next", 0));
    PreviousPageItem previous = new PreviousPageItem(config("previous", 1));
    definition.getItems().add(next); definition.getItems().add(previous);
    definition.getItems().add(new GoBackItem(config("back", 2)));
    definition.getItems().add(new CloseInventoryItem(config("close", 3)));
    View view = new View(new Navigator<>(plugin, data), definition);
    int rendered = 0;
    for (int count : new int[]{0, 1, 10, 11, 25}) {
      view.count = count; view.page = 1;
      int pages = view.getMaxPage();
      for (int p = 1; p <= pages; p++) {
        if (view.page != p) throw new AssertionError("Page advance");
        Inventory inventory = view.getInventory();
        for (int slot = 0; slot < 4; slot++) {
          var item = inventory.getItem(slot);
          if (item == null || item.getType() != Material.ARROW || !item.getItemMeta().hasDisplayName()) throw new AssertionError("Button rendering at " + slot);
        }
        rendered++;
        next.onClick(view, null);
      }
      if (view.page != pages) throw new AssertionError("Upper bound");
      while (view.page > 1) previous.onClick(view, null);
      previous.onClick(view, null);
      if (view.page != 1) throw new AssertionError("Lower bound");
    }
    return "PASS BountyHunters: " + rendered + " real inventory renders, four buttons, empty/single/multi-page navigation";
  }
}
