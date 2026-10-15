package io.paradaux.chestshop.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** One JSON line from ops/export-legacy-shops.sql; no Bukkit access during parsing. */
public record LegacyShop(UUID worldUuid, int x, int y, int z, String ownerName,
                         byte[] itemBytes, BigDecimal buyPrice, BigDecimal sellPrice,
                         int quantity, int stock, int capacity, boolean visible,
                         boolean hologram, Instant observedAt) {
    public static LegacyShop parse(String line) {
        JsonObject j = JsonParser.parseString(line).getAsJsonObject();
        LegacyShop shop = new LegacyShop(UUID.fromString(j.get("world_uuid").getAsString()),
                j.get("pos_x").getAsInt(), j.get("pos_y").getAsInt(), j.get("pos_z").getAsInt(),
                j.get("owner_name").getAsString(),
                Base64.getDecoder().decode(j.get("item_bytes").getAsString().replace("\n", "")),
                decimal(j, "buy_price"), decimal(j, "sell_price"),
                j.get("quantity").getAsInt(), j.get("stock").getAsInt(),
                j.get("estimated_capacity").getAsInt(), flag(j, "visible"), flag(j, "hologram"),
                j.get("last_updated").isJsonNull() ? null
                        : Instant.ofEpochSecond(j.get("last_updated").getAsLong()));
        if (shop.quantity <= 0 || shop.stock < 0 || shop.capacity < -1
                || shop.ownerName.isBlank() || shop.itemBytes.length == 0
                || (shop.buyPrice == null && shop.sellPrice == null)
                || (shop.buyPrice != null && shop.buyPrice.signum() < 0)
                || (shop.sellPrice != null && shop.sellPrice.signum() < 0)) {
            throw new IllegalArgumentException("Invalid legacy shop values");
        }
        return shop;
    }

    private static BigDecimal decimal(JsonObject j, String key) {
        return j.get(key).isJsonNull() ? null : j.get(key).getAsBigDecimal();
    }

    private static boolean flag(JsonObject j, String key) {
        int value = j.get(key).getAsInt();
        if (value != 0 && value != 1) throw new IllegalArgumentException("Invalid " + key);
        return value == 1;
    }
}
