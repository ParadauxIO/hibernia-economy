package io.paradaux.chestshop.services;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

/** Main-thread, plain-text shop templates. Optional providers never leak plugin API types. */
@Singleton
public final class ShopDisplayService {
    private static final Pattern TOKEN = Pattern.compile("%([a-z-]+)%");
    private final JavaPlugin plugin;
    private final ChestShopConfiguration config;
    private Function<Location, String> regionName = location -> "";

    @Inject
    public ShopDisplayService(JavaPlugin plugin, ChestShopConfiguration config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void setRegionNameProvider(Function<Location, String> provider) {
        regionName = provider;
    }

    public String location(String world, int x, int y, int z) {
        return format(config.getFindLocationFormat(), world, x, y, z);
    }

    public String previewName(String world, int x, int y, int z) {
        return format(config.getPreviewNameFormat(), world, x, y, z);
    }

    public String format(String template, String worldName, int x, int y, int z) {
        return TOKEN.matcher(template)
                .replaceAll(match -> Matcher.quoteReplacement(
                        switch (match.group(1)) {
                            case "world" -> worldName;
                            case "x" -> Integer.toString(x);
                            case "y" -> Integer.toString(y);
                            case "z" -> Integer.toString(z);
                            case "region-name" -> regionAt(worldName, x, y, z);
                            default -> match.group();
                        }));
    }

    private String regionAt(String worldName, int x, int y, int z) {
        World world = plugin.getServer().getWorld(worldName);
        return world == null ? "" : regionName.apply(new Location(world, x, y, z));
    }
}
