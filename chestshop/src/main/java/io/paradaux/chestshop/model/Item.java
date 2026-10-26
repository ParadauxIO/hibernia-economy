package io.paradaux.chestshop.model;

import lombok.Getter;
import lombok.Setter;

/**
 * A row of the {@code items} table ({@code items.db}): an auto-increment id ↔ the
 * serialized (Base64) item blob a shop references by a short code on its sign. A plain
 * POJO mapped by {@link io.paradaux.chestshop.mappers.ItemCodeMapper} (MyBatis); the
 * {@code id} is populated from the generated key on insert (PAR-282).
 *
 * @author Andrzej Pomirski
 */
@Getter
@Setter
public class Item {

    private int id;
    private String base64ItemCode;

    public Item() {
        // empty constructor, needed for MyBatis result mapping
    }

    public Item(String base64ItemCode) {
        this.base64ItemCode = base64ItemCode;
    }
}
