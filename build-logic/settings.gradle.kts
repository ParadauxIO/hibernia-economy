// =============================================================================
// build-logic — an included build that provides the monorepo's convention
// plugins (io.paradaux.*-conventions). Wired into the root build via
// `pluginManagement { includeBuild("build-logic") }` in the root
// settings.gradle.kts, so subprojects can apply the conventions by id without a
// version. Keep this build dependency-light; it only compiles Gradle plugins.
// =============================================================================

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }

    // Reuse the root's version catalog inside this included build so plugin
    // versions (e.g. shadow) are defined exactly once. An included build does
    // not inherit the root catalog automatically, so import the same TOML file.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
