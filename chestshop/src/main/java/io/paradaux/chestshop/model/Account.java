package io.paradaux.chestshop.model;

import io.paradaux.chestshop.utils.NameUtil;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;
import java.util.UUID;

/**
 * A row of the {@code accounts} table ({@code users.db}): the username ↔ UUID ↔
 * shortened-name mapping a shop sign resolves an owner against. A plain POJO mapped by
 * {@link io.paradaux.chestshop.mappers.AccountMapper} (MyBatis); {@code lastSeen} is
 * stored as epoch-millis and {@code uuid} as its string form (PAR-282).
 *
 * @author Andrzej Pomirski (Acrobot)
 */
@Getter
@Setter
public class Account {

    private String name;
    private String shortName;
    private UUID uuid;
    private Date lastSeen;
    private boolean ignoreMessages;

    public Account() {
        // empty constructor, needed for MyBatis result mapping
    }

    public Account(String name, UUID uuid) {
        this(name, NameUtil.stripUsername(name), uuid);
    }

    public Account(String name, String shortName, UUID uuid) {
        this.name = name;
        this.shortName = shortName;
        this.uuid = uuid;
    }
}
