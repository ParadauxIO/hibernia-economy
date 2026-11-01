import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.plugin.Plugin;

/** Runs against the enabled plugin; never invokes signed deletion or changes its fields. */
public final class LiveCarbonProbe {
    private LiveCarbonProbe() {}

    public static void run(Plugin actual) throws Exception {
        check(actual != null && actual.isEnabled(), "CarbonMessageDelete must be enabled");
        Class<?> type = actual.getClass();
        check(type.getName().equals("net.draycia.carbonMessageDelete.CarbonMessageDelete"), "Unexpected plugin class");
        ClassLoader loader = type.getClassLoader();
        Class<?> playerType = loader.loadClass("net.draycia.carbon.api.users.CarbonPlayer");
        Class<?> eventType = loader.loadClass("net.draycia.carbon.api.event.events.CarbonChatEvent");
        Object moderator = Proxy.newProxyInstance(loader, new Class<?>[]{playerType}, (proxy, method, args) -> {
            if (method.getName().equals("hasPermission")) return "carbon.message_delete".equals(args[0]);
            return objectMethod(proxy, method, args);
        });
        Method callback = Arrays.stream(type.getDeclaredMethods())
                .filter(m -> m.getName().equals("lambda$onEnable$0")).findFirst().orElseThrow();
        Method renderer = Arrays.stream(type.getDeclaredMethods())
                .filter(m -> m.getName().equals("lambda$onEnable$1")).findFirst().orElseThrow();
        callback.setAccessible(true);
        renderer.setAccessible(true);
        Component message = Component.text("carbon-staging-verification");
        Component invalid = actual.getConfig().getComponent("invalid_button_format", MiniMessage.miniMessage());
        Component active = actual.getConfig().getComponent("button_format", MiniMessage.miniMessage());
        check(active != null && !active.equals(Component.empty()), "Active button format must be configured");
        check(active.clickEvent() == null && (invalid == null || invalid.clickEvent() == null),
                "Configured buttons already contain click actions; inspect config before this probe");

        // Fail closed before invoking an unsigned callback on live recipients. The private
        // guard is added by our exact patch; both null inputs must already pass its contract.
        Method guard = type.getDeclaredMethod("signedOrNull", SignedMessage.class);
        guard.setAccessible(true);
        SignedMessage unsigned = SignedMessage.system("carbon-staging-verification", null);
        check(guard.invoke(null, new Object[]{null}) == null, "Absent message guard");
        check(guard.invoke(null, unsigned) == null, "Unsigned message guard");

        for (SignedMessage signed : new SignedMessage[]{null, unsigned}) {
            Object event = event(loader, eventType, signed);
            Component rendered = (Component) invoke(renderer, actual, event, moderator, moderator, message, message);
            Component expected = invalid == null || invalid.equals(Component.empty()) ? message
                    : Component.empty().append(invalid).append(message);
            check(rendered.equals(expected), "Unsigned renderer must use configured unavailable button");
            invoke(callback, actual, event, moderator);
        }

        SignedMessage.Signature signature = SignedMessage.signature(new byte[256]);
        SignedMessage signed = (SignedMessage) Proxy.newProxyInstance(loader, new Class<?>[]{SignedMessage.class},
                (proxy, method, args) -> method.getName().equals("signature") ? signature : objectMethod(proxy, method, args));
        Object signedEvent = event(loader, eventType, signed);
        Component rendered = (Component) invoke(renderer, actual, signedEvent, moderator, moderator, message, message);
        check(rendered.children().size() == 2, "Signed renderer button/message structure");
        Component button = rendered.children().get(0);
        ClickEvent click = button.clickEvent();
        check(click != null, "Signed renderer must attach an active callback");
        check(button.clickEvent(null).equals(active), "Signed renderer must retain configured button format");
        check(rendered.children().get(1).equals(message), "Signed renderer must retain chat message");
        // The callback is registered but never dispatched, so the fabricated signature
        // cannot reach real players. Adventure expires the unused callback normally.
        actual.getLogger().info("STAGING PROBE PASS: absent/unsigned renderer and callback; signed renderer and real callback registration. No signed deletion dispatched.");
    }

    private static Object event(ClassLoader loader, Class<?> eventType, SignedMessage message) {
        return Proxy.newProxyInstance(loader, new Class<?>[]{eventType}, (proxy, method, args) ->
                method.getName().equals("signedMessage") ? message : objectMethod(proxy, method, args));
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "CarbonStagingProbeProxy";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException("Unexpected probe call: " + method);
        };
    }

    private static Object invoke(Method method, Object target, Object... args) throws Exception {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception exception) throw exception;
            if (e.getCause() instanceof Error error) throw error;
            throw e;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
