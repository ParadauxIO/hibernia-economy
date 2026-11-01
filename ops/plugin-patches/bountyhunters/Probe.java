import static org.mockito.Mockito.*;
import java.lang.reflect.Field;
import java.util.List;
import net.Indyuce.bountyhunters.common.gui.Navigator;
import net.Indyuce.bountyhunters.common.gui.editable.GeneratedInventory;
import net.Indyuce.bountyhunters.common.gui.editable.item.PhysicalItem;
import net.Indyuce.bountyhunters.common.gui.editable.item.builtin.*;
import net.Indyuce.bountyhunters.common.gui.editable.placeholder.Placeholders;
import net.Indyuce.bountyhunters.common.util.AbstractPlugin;

/** Executes actual plugin placeholder/pagination code; mocks only its environment. */
public class Probe {
  static <T> T stub(Class<T> type) { return mock(type, withSettings().mockMaker("mock-maker-subclass")); }
  static <T> T real(Class<T> type) { return mock(type, withSettings().mockMaker("mock-maker-subclass").defaultAnswer(CALLS_REAL_METHODS)); }
  static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  public static void main(String[] args) throws Exception {
    AbstractPlugin plugin = stub(AbstractPlugin.class);
    when(plugin.getPlaceholderParser()).thenReturn((player, text) -> text.replace("%player_name%", "TestPlayer"));
    Navigator navigator = stub(Navigator.class);
    when(navigator.getPlugin()).thenReturn(plugin);
    GeneratedInventory inventory = stub(GeneratedInventory.class);
    when(inventory.getNavigator()).thenReturn(navigator);
    when(inventory.hasPagination()).thenReturn(true);
    for (Class<? extends PhysicalItem> type : List.of(NextPageItem.class, PreviousPageItem.class, GoBackItem.class, CloseInventoryItem.class)) {
      PhysicalItem item = real(type);
      Placeholders placeholders = item.getPlaceholders(inventory, 0);
      String result = placeholders.apply(null, "&aNext %player_name% {unknown}");
      check(result.equals("\u00a7aNext TestPlayer {unknown}"), "Parser context lost for " + type + ": " + result);
    }
    NextPageItem next = real(NextPageItem.class);
    PreviousPageItem previous = real(PreviousPageItem.class);
    for (Object item : List.of(next, previous)) {
      Field hide = item.getClass().getSuperclass().getDeclaredField("hideIfNoPage");
      hide.setAccessible(true); hide.setBoolean(item, true);
    }
    // Real page-count calculation for zero, one, and multiple pages.
    Field perPage = GeneratedInventory.class.getDeclaredField("perPage");
    perPage.setAccessible(true); perPage.setInt(inventory, 10);
    when(inventory.computeMaxPage(anyInt())).thenCallRealMethod();
    for (int count : new int[]{0, 1, 10, 11, 25}) {
      int pages = inventory.computeMaxPage(count);
      check(pages == Math.max(1, (count + 9) / 10), "Wrong page count");
      when(inventory.getMaxPage()).thenReturn(pages);
      inventory.page = 1;
      check(!previous.isDisplayed(inventory), "Previous visible on first page");
      check(next.isDisplayed(inventory) == (pages > 1), "Next visibility");
      previous.onClick(inventory, null);
      check(inventory.page == 1, "Previous crossed lower bound");
      for (int i = 1; i < pages; i++) next.onClick(inventory, null);
      check(inventory.page == pages && !next.isDisplayed(inventory), "Last page boundary");
      next.onClick(inventory, null);
      check(inventory.page == pages, "Next crossed upper bound");
      while (inventory.page > 1) previous.onClick(inventory, null);
      check(inventory.page == 1, "Reverse navigation");
    }
    System.out.println("PASS four button factories, parser/color preservation, empty/single/multi-page navigation");
  }
}
