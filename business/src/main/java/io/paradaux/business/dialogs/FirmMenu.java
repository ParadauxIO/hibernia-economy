package io.paradaux.business.dialogs;

import io.paradaux.hibernia.framework.exceptions.BadCommandException;
import io.paradaux.hibernia.framework.usher.*;
import io.paradaux.hibernia.framework.usher.annotations.*;
import io.paradaux.hibernia.framework.usher.input.DialogInputSpec.OptionSpec;
import io.paradaux.hibernia.framework.usher.spi.DialogHandler;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;

/** Native dialogs contain no transferable items. All writes re-enter the normal command dispatcher. */
@Dialog("firm-menu")
public final class FirmMenu implements DialogHandler {
    public record Choice(String id, String name) {}

    public static final class State {
        final List<Choice> choices;
        final Integer firmId;
        final String name;
        final String balance;
        final List<String> accounts;
        final List<String> staff;
        final AtomicBoolean submitted = new AtomicBoolean();

        public State(List<Choice> choices) {
            this.choices = List.copyOf(choices);
            firmId = null;
            name = balance = "";
            accounts = staff = List.of();
        }

        public State(int id, String name, String balance, List<String> accounts, List<String> staff) {
            choices = List.of();
            firmId = id;
            this.name = name;
            this.balance = balance;
            this.accounts = List.copyOf(accounts);
            this.staff = List.copyOf(staff);
        }
    }

    @Screen
    public DialogView main(@Model State state) {
        if (state.firmId == null) {
            var view = DialogView.multiAction("business.menu.title");
            if (state.choices.isEmpty())
                return view.body("business.menu.empty")
                        .exit("business.menu.close")
                        .build();
            return view.option(
                            "firm",
                            Text.key("business.menu.firm"),
                            state.choices.stream()
                                    .map(c -> new OptionSpec(
                                            c.id(), literal(c.name()), c.equals(state.choices.getFirst())))
                                    .toList())
                    .button("business.menu.select", "select")
                    .exit("business.menu.close")
                    .build();
        }
        return DialogView.multiAction(Text.key("business.menu.overview", "firm", state.name))
                .body(Text.key("business.menu.balance", "balance", state.balance))
                .open("business.menu.money", "money")
                .open("business.menu.staff", "staff")
                .open("business.menu.accounts", "accounts")
                .button("business.menu.refresh", "refresh")
                .exit("business.menu.close")
                .columns(1)
                .build();
    }

    @Action("select")
    public void select(@Input("firm") String id, @Model State state, DialogFlow flow) {
        if (state.choices.stream().noneMatch(c -> c.id().equals(id))) throw invalid();
        dispatch(state, flow, "firm menu " + id);
    }

    @Action("refresh")
    public void refresh(@Model State state, DialogFlow flow) {
        dispatch(state, flow, "firm menu " + state.firmId);
    }

    @Screen("money")
    public DialogView money(DialogFlow flow) {
        return form(
                        "business.menu.money",
                        flow,
                        Operation.DEPOSIT,
                        Operation.WITHDRAW,
                        Operation.PAY_PLAYER,
                        Operation.PAY_FIRM,
                        Operation.PAY_INTO)
                .text("amount", Text.key("business.menu.amount"))
                .text("target", Text.key("business.menu.target-money"))
                .body("business.menu.money-help")
                .build();
    }

    @Screen("staff")
    public DialogView staff(@Model State state, DialogFlow flow) {
        return form("business.menu.staff", flow, Operation.HIRE, Operation.FIRE)
                .body(literal(String.join("\n", state.staff)))
                .text("target", Text.key("business.menu.target-player"))
                .build();
    }

    @Screen("accounts")
    public DialogView accounts(@Model State state, DialogFlow flow) {
        return form("business.menu.accounts", flow, Operation.CREATE_ACCOUNT, Operation.DEFAULT_ACCOUNT)
                .body(literal(String.join("\n", state.accounts)))
                .text("target", Text.key("business.menu.target-account"))
                .build();
    }

    private DialogView.Builder form(String title, DialogFlow flow, Operation... operations) {
        var allowed = Arrays.stream(operations)
                .filter(o -> flow.player().hasPermission(o.permission))
                .toList();
        var view = DialogView.multiAction(title).exit(ButtonSpec.back(Text.key("business.menu.back")));
        if (allowed.isEmpty()) return view.body("business.general.no-permission");
        return view.option(
                        "operation",
                        Text.key("business.menu.action"),
                        allowed.stream()
                                .map(o -> new OptionSpec(o.name(), Text.key(o.label), o == allowed.getFirst()))
                                .toList())
                .button("business.menu.submit", "submit");
    }

    @Action("submit")
    public void submit(
            @Input("operation") String operation,
            @Input("target") String target,
            @Input("amount") String amount,
            @Model State state,
            DialogFlow flow) {
        Operation selected;
        try {
            selected = Operation.valueOf(operation);
        } catch (RuntimeException e) {
            throw invalid();
        }
        if (!flow.player().hasPermission(selected.permission)) {
            throw new io.paradaux.hibernia.framework.exceptions.NoPermissionException("business.general.no-permission");
        }
        dispatch(state, flow, selected.command(state.firmId, target, amount));
    }

    private static void dispatch(State state, DialogFlow flow, String command) {
        // A second click (including an old screen's callback) cannot repeat a payment or staff mutation.
        if (!state.submitted.compareAndSet(false, true)) return;
        flow.close();
        if (flow.player().isOnline() && flow.player().hasPermission("business.menu"))
            flow.player().performCommand(command);
    }

    private static Text literal(String text) {
        return Text.of(Component.text(text));
    }

    private static BadCommandException invalid() {
        return new BadCommandException("business.menu.invalid");
    }

    public enum Operation {
        DEPOSIT("deposit", "business.finance", "business.menu.deposit", true, false),
        WITHDRAW("withdraw", "business.finance", "business.menu.withdraw", true, false),
        PAY_PLAYER("pay player", "business.finance", "business.menu.pay-player", true, true),
        PAY_FIRM("pay business", "business.finance", "business.menu.pay-firm", true, true),
        PAY_INTO("pay into", "business.pay", "business.menu.pay-into", true, false),
        HIRE("hire", "business.staff.offer", "business.menu.hire", false, true),
        FIRE("fire", "business.staff.fire", "business.menu.fire", false, true),
        CREATE_ACCOUNT("account create", "business.account.create", "business.menu.create-account", false, true),
        DEFAULT_ACCOUNT(
                "account setdefault", "business.account.setdefault", "business.menu.default-account", false, true);
        final String route, permission, label;
        final boolean money, needsTarget;

        Operation(String route, String permission, String label, boolean money, boolean needsTarget) {
            this.route = route;
            this.permission = permission;
            this.label = label;
            this.money = money;
            this.needsTarget = needsTarget;
        }

        public String command(Integer firmId, String target, String amount) {
            if (firmId == null) throw invalid();
            // One argument per field: reject whitespace/control characters rather than allowing route injection.
            String suffix = needsTarget ? " " + token(target) : "";
            if (money) suffix += " " + token(amount);
            return "firm " + route + " " + firmId + suffix;
        }

        private static String token(String value) {
            if (value == null
                    || value.isBlank()
                    || value.length() > 100
                    || value.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)))
                throw invalid();
            return value;
        }
    }
}
