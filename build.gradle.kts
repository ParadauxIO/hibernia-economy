// =============================================================================
// Hibernia Economy monorepo — root build.
//
// Owns no source. It centralises the external plugin versions (so each
// subproject applies them WITHOUT a version — a single build rejects the same
// versioned plugin being requested twice) and sets the shared coordinates.
//
//   ./gradlew build            assemble + test every subproject
//   ./gradlew :treasury:build  build one project
//   ./gradlew :chestshop:shadowJar            build chestshop-<version>.jar
// =============================================================================

plugins {
    // Shadow version is defined once in the catalog (gradle/libs.versions.toml,
    // [plugins].shadow) and shared with the build-logic classpath dep so the two
    // never drift. See build-logic/build.gradle.kts.
    alias(libs.plugins.shadow) apply false
    id("org.springframework.boot") version "4.1.0" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    // NOTE: org.flywaydb.flyway is intentionally NOT centralized here. It must
    // load on the same buildscript classloader as the JDBC driver, so it's
    // declared with its version inside economy-flyway/build.gradle.kts.
}

// The treasury-api / business-api subprojects derive their version from
// rootProject.version (they publish under it), so set it here. The publish
// workflows pass -Pversion=… to cut a release, so honour that property; default
// to the dev snapshot otherwise. Plugins/apps that pin their own version in their
// build.gradle.kts override this for themselves.
allprojects {
    group = "io.paradaux"
    version = providers.gradleProperty("version").orElse("2.3.0-SNAPSHOT").get()
}

// =============================================================================
// Release bundle.
//
//   ./gradlew release        build every Paper plugin into a clean release/ folder
//
// Gathers the shaded jar of each deployable Paper plugin into `release/` at the
// repo root. It's a Sync (not a Copy), so the destination is mirrored to exactly
// the current set of jars — stale artifacts from a previous run are removed,
// giving a clean folder every time.
//
// treasury-rest-api is intentionally excluded: it ships as a container image
// (bootJar → Docker → Harbor/Argo CD), not as a server plugin.
// =============================================================================
val pluginProjectPaths = listOf(
    ":treasury",
    ":business",
    ":treasury-api-plugin",
    ":chestshop",
)

tasks.register<Sync>("release") {
    group = "distribution"
    description = "Build every Paper plugin and stage the jars into a clean release/ folder."

    into(layout.projectDirectory.dir("release"))

    pluginProjectPaths.forEach { path ->
        val shadowJar = project(path).tasks
            .named<org.gradle.api.tasks.bundling.AbstractArchiveTask>("shadowJar")
        from(shadowJar.flatMap { it.archiveFile })
    }
}
