package io.paradaux.business.integration;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.business.Business;
import io.paradaux.business.services.RegionValidator;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Validates region/plot ids against the Realty plugin's {@code RealtyBackend}
 * service, obtained at runtime from Bukkit's {@code ServicesManager}.
 *
 * <p><b>Why reflection.</b> Realty is a vendored submodule but it is not
 * published to any Maven repository the {@code business} build resolves against
 * (its artifacts default to an unset {@code deployUrl}), so we cannot add a
 * {@code compileOnly} dependency without breaking CI and fresh checkouts the way
 * we do for Treasury/CarbonChat. Realty is therefore a soft dependency reached
 * purely reflectively; when it is absent every lookup reports "no provider"
 * ({@link Optional#empty()}) and the caller fails open.
 *
 * <p>A firm HQ "plot" is a WorldGuard region id with no world attached, so we
 * probe every loaded world and treat the region as valid if Realty knows it in
 * any of them ({@code RealtyBackend.getRegionState(regionId, worldId) != null}).
 *
 * <p>This class is intentionally excluded from the coverage gate (Bukkit +
 * reflection glue that cannot be exercised without a running server with Realty
 * installed); the decision logic that can be tested lives in
 * {@code FirmAreaShopServiceImpl}.
 */
@Singleton
public class RealtyRegionValidator implements RegionValidator {

    private static final String BACKEND_CLASS = "io.github.md5sha256.realty.api.RealtyBackend";
    private static final Logger LOG = Logger.getLogger("Business");

    private final Business plugin;

    /** Cached reflective handle to {@code RealtyBackend#getRegionState}; resolved lazily. */
    private Method getRegionState;
    private boolean resolutionAttempted;

    @Inject
    public RealtyRegionValidator(Business plugin) {
        this.plugin = plugin;
    }

    @Override
    public Optional<Boolean> validate(String regionId) {
        Object backend = resolveBackend();
        if (backend == null) {
            return Optional.empty();
        }

        Method method = getRegionState;
        if (method == null) {
            return Optional.empty();
        }

        // HQ validation is reachable from an @Async route (FirmCommands.setHq → …),
        // so the world list must be snapshotted on the main thread rather than
        // iterating Bukkit.getWorlds() off-thread (plugin-architecture/0004).
        List<UUID> worldUids;
        try {
            worldUids = snapshotWorldUids();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException ex) {
            LOG.log(Level.WARNING, "Failed to snapshot worlds for Realty region lookup of '"
                    + regionId + "'; treating as unvalidated.", ex);
            return Optional.empty();
        }

        try {
            for (UUID worldUid : worldUids) {
                Object state = method.invoke(backend, regionId, worldUid);
                if (state != null) {
                    return Optional.of(true);
                }
            }
            // Realty is present and recognised no world holding this region.
            return Optional.of(false);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            LOG.log(Level.WARNING, "Realty region lookup failed for '" + regionId
                    + "'; treating as unvalidated.", ex);
            return Optional.empty();
        }
    }

    /**
     * Returns the loaded worlds' UUIDs, always read on the server main thread.
     * When called off-thread (the @Async HQ path) the read is hopped onto the main
     * thread via the scheduler and awaited; on the main thread it reads directly.
     */
    private List<UUID> snapshotWorldUids() throws InterruptedException, ExecutionException {
        if (Bukkit.isPrimaryThread()) {
            return collectWorldUids();
        }
        return Bukkit.getScheduler().callSyncMethod(plugin, this::collectWorldUids).get();
    }

    private List<UUID> collectWorldUids() {
        List<UUID> uids = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            uids.add(world.getUID());
        }
        return uids;
    }

    /**
     * @return the live {@code RealtyBackend} service instance, or {@code null}
     *         when Realty is not installed / not yet registered.
     */
    private Object resolveBackend() {
        try {
            Class<?> backendClass = Class.forName(BACKEND_CLASS);
            RegisteredServiceProvider<?> rsp =
                    Bukkit.getServicesManager().getRegistration(backendClass);
            if (rsp == null) {
                return null;
            }
            Object provider = rsp.getProvider();
            if (!resolutionAttempted) {
                resolutionAttempted = true;
                getRegionState = backendClass.getMethod("getRegionState", String.class, java.util.UUID.class);
            }
            return provider;
        } catch (ClassNotFoundException ex) {
            return null; // Realty not on the classpath at all.
        } catch (NoSuchMethodException ex) {
            resolutionAttempted = true;
            LOG.log(Level.WARNING, "RealtyBackend#getRegionState not found; "
                    + "Realty API may have changed. HQ validation disabled.", ex);
            return null;
        }
    }
}
