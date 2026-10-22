package io.paradaux.business.dialogs;

import com.google.inject.Inject;
import io.paradaux.hibernia.framework.curator.*;
import io.paradaux.hibernia.framework.curator.annotations.*;
import io.paradaux.hibernia.framework.curator.spi.MenuHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.hibernia.framework.usher.DialogManager;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Curator owns inventory cancellation, paging and navigation; Usher owns text input. */
public final class FirmDashboard implements MenuHandler {
    private final Message message;
    private final DialogManager dialogs;

    @Inject
    public FirmDashboard(Message message, DialogManager dialogs) {
        this.message = message;
        this.dialogs = dialogs;
    }

    @Screen
    public MenuView main(@Model FirmMenu.State state, MenuFlow flow) {
        if (state.firmId == null) {
            var entries = state.choices.stream()
                    .map(c -> MenuItem.button(icon(Material.BRICKS, Component.text(c.name())), "select", c.id()))
                    .toList();
            if (entries.isEmpty()) entries = List.of(MenuItem.display(button(Material.PAPER, "business.menu.empty")));
            var view =
                    MenuView.chest(message.component("business.menu.title"), 6).content(0, 44, entries);
            return navigation(view, flow, state.choices.size()).build();
        }
        return MenuView.chest(message.component("business.menu.overview", "firm", state.name), 3)
                .slot(
                        4,
                        MenuItem.display(icon(
                                Material.GOLD_INGOT,
                                message.component(
                                        "business.menu.balance",
                                        "balance",
                                        flow.player().hasPermission("business.finance") ? state.balance : "—"))))
                .slot(11, MenuItem.button(button(Material.GOLD_NUGGET, "business.menu.money"), "money", null))
                .slot(13, MenuItem.button(button(Material.PLAYER_HEAD, "business.menu.staff"), "staff", null))
                .slot(15, MenuItem.button(button(Material.CHEST, "business.menu.accounts"), "accounts", null))
                .slot(18, MenuItem.button(button(Material.ARROW, "business.menu.firms"), "firms", null))
                .slot(22, MenuItem.button(button(Material.CLOCK, "business.menu.refresh"), "refresh", null))
                .slot(26, MenuItem.button(button(Material.BARRIER, "business.menu.close"), "close", null))
                .fill(MenuItem.display(filler()))
                .build();
    }

    @Screen("staff")
    public MenuView staff(@Model FirmMenu.State state, MenuFlow flow) {
        return listing("business.menu.staff", state.staff, "business.staff.list", Material.PLAYER_HEAD, flow);
    }

    @Screen("accounts")
    public MenuView accounts(@Model FirmMenu.State state, MenuFlow flow) {
        return listing("business.menu.accounts", state.accounts, "business.account.list", Material.CHEST, flow);
    }

    private MenuView listing(String title, List<String> lines, String permission, Material material, MenuFlow flow) {
        var visible = flow.player().hasPermission(permission) ? lines : List.<String>of();
        var entries = visible.stream()
                .map(line -> MenuItem.display(icon(material, Component.text(line))))
                .toList();
        if (entries.isEmpty())
            entries = List.of(MenuItem.display(button(
                    Material.PAPER,
                    flow.player().hasPermission(permission)
                            ? "business.menu.no-entries"
                            : "business.general.no-permission")));
        var view = MenuView.chest(message.component(title), 6)
                .content(0, 44, entries)
                .slot(45, MenuItem.button(button(Material.ARROW, "business.menu.back"), "back", null))
                .slot(49, MenuItem.button(button(Material.WRITABLE_BOOK, "business.menu.manage"), "manage", null));
        return navigation(view, flow, visible.size()).build();
    }

    @Action("select")
    public void select(MenuContext click, @Model FirmMenu.State state, MenuFlow flow) {
        String id = click.payload(String.class);
        if (state.choices.stream().anyMatch(c -> c.id().equals(id))) dispatch(state, flow, "firm menu " + id);
    }

    @Action("money")
    public void money(@Model FirmMenu.State state, MenuFlow flow) {
        form(state, flow, "money");
    }

    @Action("staff")
    public void openStaff(MenuFlow flow) {
        flow.open("staff");
    }

    @Action("accounts")
    public void openAccounts(MenuFlow flow) {
        flow.open("accounts");
    }

    @Action("manage")
    public void manage(@Model FirmMenu.State state, MenuFlow flow) {
        form(state, flow, flow.current());
    }

    private void form(FirmMenu.State state, MenuFlow flow, String screen) {
        if (!flow.player().hasPermission("business.menu") || !state.submitted.compareAndSet(false, true)) return;
        var form = new FirmMenu.State(state.firmId, state.name, state.balance, state.accounts, state.staff);
        flow.close();
        dialogs.open(flow.player(), FirmMenu.class, screen, form);
    }

    @Action("refresh")
    public void refresh(@Model FirmMenu.State state, MenuFlow flow) {
        dispatch(state, flow, "firm menu " + state.firmId);
    }

    @Action("firms")
    public void firms(@Model FirmMenu.State state, MenuFlow flow) {
        dispatch(state, flow, "firm menu");
    }

    private void dispatch(FirmMenu.State state, MenuFlow flow, String command) {
        if (!state.submitted.compareAndSet(false, true)) return;
        flow.close();
        if (flow.player().isOnline() && flow.player().hasPermission("business.menu"))
            flow.player().performCommand(command);
    }

    @Action("back")
    public void back(MenuFlow flow) {
        flow.back();
    }

    @Action("next")
    public void next(MenuFlow flow) {
        flow.nextPage();
    }

    @Action("previous")
    public void previous(MenuFlow flow) {
        flow.previousPage();
    }

    @Action("close")
    public void close(MenuFlow flow) {
        flow.close();
    }

    private MenuView.Builder navigation(MenuView.Builder view, MenuFlow flow, int count) {
        if (flow.page() > 0)
            view.slot(48, MenuItem.button(button(Material.ARROW, "business.menu.previous"), "previous", null));
        if ((long) (flow.page() + 1) * 45 < count)
            view.slot(50, MenuItem.button(button(Material.ARROW, "business.menu.next"), "next", null));
        return view.slot(53, MenuItem.button(button(Material.BARRIER, "business.menu.close"), "close", null))
                .fill(MenuItem.display(filler()));
    }

    private ItemStack button(Material material, String key) {
        return icon(material, message.component(key));
    }

    private static ItemStack icon(Material material, Component name) {
        var item = new ItemStack(material);
        item.editMeta(meta -> meta.displayName(name.decoration(TextDecoration.ITALIC, false)));
        return item;
    }

    private static ItemStack filler() {
        return icon(Material.GRAY_STAINED_GLASS_PANE, Component.empty());
    }
}
