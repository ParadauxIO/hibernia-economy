package io.paradaux.business.services;

import io.paradaux.business.model.FirmPlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FirmPlayerService {

    Optional<FirmPlayer> findByUuid(UUID uuid);
    Optional<FirmPlayer> findByUuid(String uuid);
    Optional<FirmPlayer> findByName(String name);

    List<FirmPlayer> searchByPrefix(String prefix, int limit);

    boolean isOnline(FirmPlayer player);
    boolean isOnline(UUID uuid);

    Player getPlayer(FirmPlayer player);
}
