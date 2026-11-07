package io.paradaux.treasury.startup;

import static org.assertj.core.api.Assertions.*;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ServiceLoader;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

class ShadedMigrationRuntimeTest {
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"}) // Plugin API belongs to the isolated plugin classloader.
    void shippedJarContainsUnchangedMigrationsAndWorkingMysqlServiceProvider() throws Exception {
        Path artifact = Path.of(System.getProperty("treasury.pluginJar"));
        try (var jar = new JarFile(artifact.toFile());
                var sources = Files.list(Path.of("../economy-flyway/src/main/resources/db/migration"));
                var loader = new URLClassLoader(
                        new java.net.URL[] {artifact.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            for (Path source :
                    sources.filter(path -> path.toString().endsWith(".sql")).toList()) {
                var entry = jar.getJarEntry("db/migration/" + source.getFileName());
                assertThat(entry).as("bundled %s", source.getFileName()).isNotNull();
                try (var input = jar.getInputStream(entry)) {
                    assertThat(input.readAllBytes()).isEqualTo(Files.readAllBytes(source));
                }
            }
            Class pluginType = loader.loadClass("org.flywaydb.core.extensibility.Plugin");
            var providers = ServiceLoader.load(pluginType, loader).stream().toList();
            assertThat(providers).anySatisfy(provider -> {
                Object instance = ((ServiceLoader.Provider<?>) provider).get();
                assertThat(instance.getClass().getName()).isEqualTo("org.flywaydb.database.mysql.MySQLDatabaseType");
            });
            // Exercise Flyway configuration using only the shipped runtime (including relocated Jackson).
            var flyway = loader.loadClass("org.flywaydb.core.Flyway");
            var configuration = flyway.getMethod("configure", ClassLoader.class).invoke(null, loader);
            assertThat(configuration.getClass().getMethod("load").invoke(configuration))
                    .isNotNull();
        }
    }
}
