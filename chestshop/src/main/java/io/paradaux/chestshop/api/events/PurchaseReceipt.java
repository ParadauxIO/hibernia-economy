package io.paradaux.chestshop.api.events;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Material;

/** Immutable snapshot of goods and price from a successfully settled client BUY. */
public record PurchaseReceipt(UUID tradeId, UUID buyerId, BigDecimal price, List<PurchasedItem> items) {
    public PurchaseReceipt {
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(buyerId, "buyerId");
        Objects.requireNonNull(price, "price");
        if (price.signum() < 0) throw new IllegalArgumentException("Negative purchase price");
        items = List.copyOf(items);
        if (items.isEmpty()) throw new IllegalArgumentException("Purchase requires goods");
    }

    /** Material and actual purchased quantity; does not claim to describe custom item metadata. */
    public record PurchasedItem(Material material, int quantity) {
        public PurchasedItem {
            Objects.requireNonNull(material, "material");
            if (material.isAir() || quantity <= 0)
                throw new IllegalArgumentException("Purchase requires positive goods");
        }
    }
}
