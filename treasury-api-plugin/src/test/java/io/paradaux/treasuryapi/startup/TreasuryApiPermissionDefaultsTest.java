package io.paradaux.treasuryapi.startup;

import io.paradaux.hibernia.framework.commander.annotations.Permission;
import io.paradaux.treasuryapi.commands.TreasuryAPICommand;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@code plugin.yml} defaults behind every {@code /treasuryapi} route.
 *
 * <p>PAR-336: in 2.1.0 the root {@code treasuryapi.command} node was
 * {@code default: op}, and no LuckPerms group granted it, so business owners
 * couldn't reach {@code /treasuryapi business issue} even though the
 * subcommand's own node was {@code default: true}. A route is only reachable
 * when the root node AND its own node both pass, so this checks them together.
 * The live suite can't catch the regression because its helpers wrap commands
 * in {@code Ops.run}.
 */
class TreasuryApiPermissionDefaultsTest {

    /** Privileged on purpose: grants any explorer role, for staff recovery. */
    private static final Set<String> OP_ONLY = Set.of("treasuryapi.ui.user");

    @Test
    void everyRoutePermissionIsDeclared() throws Exception {
        Map<String, org.bukkit.permissions.Permission> declared = declaredPermissions();

        assertThat(declared.keySet()).containsAll(routePermissions());
    }

    @Test
    void playerRoutesAreOpenByDefault() throws Exception {
        Map<String, org.bukkit.permissions.Permission> declared = declaredPermissions();

        for (String node : routePermissions()) {
            PermissionDefault expected = OP_ONLY.contains(node) ? PermissionDefault.OP : PermissionDefault.TRUE;
            assertThat(declared.get(node).getDefault())
                    .as("plugin.yml default for %s", node)
                    .isEqualTo(expected);
        }
    }

    /** The class-level root node plus every method-level route node. */
    private static Set<String> routePermissions() {
        Set<String> nodes = new TreeSet<>();
        nodes.add(TreasuryAPICommand.class.getAnnotation(Permission.class).value());
        for (Method m : TreasuryAPICommand.class.getDeclaredMethods()) {
            Permission p = m.getAnnotation(Permission.class);
            if (p != null) nodes.add(p.value());
        }
        return nodes;
    }

    /** Parsed with Bukkit's own loader, from the processed plugin.yml on the classpath. */
    private static Map<String, org.bukkit.permissions.Permission> declaredPermissions() throws Exception {
        try (InputStream in = TreasuryApiPermissionDefaultsTest.class.getClassLoader().getResourceAsStream("plugin.yml")) {
            assertThat(in).as("plugin.yml on the test classpath").isNotNull();
            return new PluginDescriptionFile(in).getPermissions().stream()
                    .collect(Collectors.toMap(org.bukkit.permissions.Permission::getName, Function.identity()));
        }
    }
}
