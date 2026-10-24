package io.paradaux.business.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.business.mappers.FirmPlayerMapper;
import io.paradaux.business.model.FirmPlayer;
import io.paradaux.business.services.FirmPlayerService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Singleton
public class FirmPlayerServiceImpl implements FirmPlayerService {

    private final FirmPlayerMapper mapper;

    @Inject
    public FirmPlayerServiceImpl(FirmPlayerMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<FirmPlayer> findByUuid(UUID uuid) {
        if (uuid == null) return Optional.empty();
        return Optional.ofNullable(mapper.getByUuid(uuid.toString()));
    }

    public Optional<FirmPlayer> findByUuid(String uuid) {
        if (uuid == null) return Optional.empty();
        return Optional.ofNullable(mapper.getByUuid(uuid));
    }

    @Override
    public Optional<FirmPlayer> findByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return Optional.ofNullable(mapper.getByName(name));
    }

    @Override
    public List<FirmPlayer> searchByPrefix(String prefix, int limit) {
        int resolvedLimit = (limit <= 0 || limit > 100) ? 20 : limit;
        String p = (prefix == null) ? "" : prefix.trim();
        return mapper.searchByPrefix(p, resolvedLimit);
    }

    @Override
    public boolean isOnline(FirmPlayer player) {
        return isOnline(player.getUniqueId());
    }

    @Override
    public boolean isOnline(UUID uuid) {
        return Bukkit.getPlayer(uuid) != null;
    }

    @Override
    public Player getPlayer(FirmPlayer player) {
        return Bukkit.getPlayer(player.getUniqueId());
    }
}