package io.paradaux.chestshop.model;

import lombok.Getter;
import lombok.Setter;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Data Transfer Object for Player objects
 *
 * Since Bukkit API is not thread-safe, this should work
 * @author Andrzej Pomirski
 */
@Getter
@Setter
public class PlayerSnapshot {

    private UUID uniqueId;
    private String name;

    public PlayerSnapshot(UUID uuid, String name) {
        this.uniqueId = uuid;
        this.name = name;
    }

    public PlayerSnapshot(Player player) {
        this.uniqueId = player.getUniqueId();
        this.name = player.getName();
    }
}
