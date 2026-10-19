package io.paradaux.chestshop.integration;

import io.paradaux.hibernia.framework.usher.spi.BedrockSupport;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Reflective optional APIs keep Floodgate/Geyser off the required runtime classpath. */
public final class BedrockIntegration implements BedrockSupport {
    @Override
    public boolean isBedrock(Player player) {
        return detect(
                        "floodgate",
                        "org.geysermc.floodgate.api.FloodgateApi",
                        "getInstance",
                        "isFloodgatePlayer",
                        player)
                || detect("Geyser-Spigot", "org.geysermc.geyser.api.GeyserApi", "api", "isBedrockPlayer", player);
    }

    private static boolean detect(String name, String type, String instance, String method, Player player) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        if (plugin == null || !plugin.isEnabled()) return false;
        try {
            Class<?> api = Class.forName(type, true, plugin.getClass().getClassLoader());
            Object receiver = api.getMethod(instance).invoke(null);
            return Boolean.TRUE.equals(
                    api.getMethod(method, java.util.UUID.class).invoke(receiver, player.getUniqueId()));
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("Installed " + name + " has an incompatible Bedrock detection API", e);
        }
    }
}
