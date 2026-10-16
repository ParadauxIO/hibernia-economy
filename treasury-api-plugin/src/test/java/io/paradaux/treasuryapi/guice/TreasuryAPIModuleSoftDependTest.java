package io.paradaux.treasuryapi.guice;

import io.paradaux.hibernia.framework.exceptions.ConflictException;
import net.luckperms.api.LuckPerms;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * LuckPerms is a {@code softdepend}: the plugin must enable, and every command
 * handler must resolve, on a server that does not have it. Guice validates the
 * whole dependency graph of every explicit binding at {@code createInjector} —
 * including the target of an injected {@code Provider<T>} — so a service that
 * reaches the LuckPerms-only reconciliation task, however lazily, takes the whole
 * plugin down at startup unless the dependency is genuinely optional.
 */
class TreasuryAPIModuleSoftDependTest {

    private static final String PROBE = "io.paradaux.treasuryapi.guice.SoftDependInjectorProbe";

    // ---- LuckPerms API on the classpath, but no LuckPerms service registered ----

    @Test
    void injectorBuilds_whenLuckPermsServiceIsNotRegistered() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            ServicesManager services = mock(ServicesManager.class);
            bukkit.when(Bukkit::getServicesManager).thenReturn(services);
            when(services.load(LuckPerms.class)).thenReturn(null);

            assertDoesNotThrow(SoftDependInjectorProbe::createInjector);
        }
    }

    @Test
    void selfSync_isUnavailable_whenLuckPermsServiceIsNotRegistered() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            ServicesManager services = mock(ServicesManager.class);
            bukkit.when(Bukkit::getServicesManager).thenReturn(services);
            when(services.load(LuckPerms.class)).thenReturn(null);

            ConflictException ex = assertThrows(ConflictException.class, SoftDependInjectorProbe::selfSync);
            assertEquals("treasuryapi.ui.sync.unavailable", ex.getMessage());
        }
    }

    @Test
    void selfSync_reachesLuckPerms_whenTheServiceIsRegistered() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            ServicesManager services = mock(ServicesManager.class);
            bukkit.when(Bukkit::getServicesManager).thenReturn(services);
            // A broken registered provider must report sync.failed, not silently
            // report success or claim the optional integration is unavailable.
            when(services.load(LuckPerms.class)).thenReturn(mock(LuckPerms.class));

            ConflictException ex = assertThrows(ConflictException.class, SoftDependInjectorProbe::selfSync);
            assertEquals("treasuryapi.ui.sync.failed", ex.getMessage());
        }
    }

    // ---- LuckPerms absent entirely: net.luckperms.* cannot be loaded ----

    @Test
    void injectorBuilds_whenLuckPermsClassesAreAbsent() {
        // A LinkageError (missing net.luckperms class) is rethrown unwrapped.
        assertDoesNotThrow(() -> invokeWithoutLuckPerms("createInjector"));
    }

    @Test
    void selfSync_isUnavailable_whenLuckPermsClassesAreAbsent() {
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> invokeWithoutLuckPerms("selfSync"));
        ConflictException ex = assertInstanceOf(ConflictException.class, thrown.getCause());
        assertEquals("treasuryapi.ui.sync.unavailable", ex.getMessage());
    }

    private static void invokeWithoutLuckPerms(String method) throws Exception {
        ClassLoader isolated = new LuckPermsHidingClassLoader(TreasuryAPIModuleSoftDependTest.class.getClassLoader());
        try {
            isolated.loadClass(PROBE).getMethod(method).invoke(null);
        } catch (InvocationTargetException e) {
            // Unwrap linkage failures so a missing-class regression reads as what it is.
            if (e.getCause() instanceof LinkageError le) throw le;
            throw e;
        }
    }

    /**
     * Re-defines the plugin's own classes (so every reference they make resolves
     * through this loader) and refuses to load anything under {@code net.luckperms},
     * reproducing the plugin classloader of a server without LuckPerms.
     */
    private static final class LuckPermsHidingClassLoader extends ClassLoader {

        LuckPermsHidingClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("net.luckperms.")) {
                throw new ClassNotFoundException(name);
            }
            if (!name.startsWith("io.paradaux.treasuryapi.")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = defineFromParent(name);
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }

        private Class<?> defineFromParent(String name) throws ClassNotFoundException {
            String resource = name.replace('.', '/') + ".class";
            try (InputStream in = getParent().getResourceAsStream(resource)) {
                if (in == null) throw new ClassNotFoundException(name);
                byte[] bytes = in.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }
}
