// =============================================================================
// Hibernia Economy monorepo — single root Gradle build.
//
// One settings file, one wrapper, one build graph. Every JVM project is a
// subproject here; cross-project dependencies are wired with project(...) deps
// (see each build.gradle.kts), so nothing is resolved from a remote snapshot.
//
// Excluded on purpose:
//   * economy-explorer  — Node/npm + Next.js, not a JVM build.
//   * hibernia-framework, realty — git submodules, consumed as published
//     artifacts (io.paradaux:*).
// =============================================================================

// build-logic is an included build that provides the io.paradaux.*-conventions
// plugins (the shared plugin-build boilerplate). Including it under
// pluginManagement makes those plugins applyable by id, without a version, in
// any subproject's plugins { } block.
pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "hibernia-economy"

include(":common")

// Shared test-kit: startup + message-key assertions consumed by the Paper plugins
// as testImplementation(project(":test-support")). Not shaded, never a runtime dep.
include(":test-support")

include(":treasury")
include(":treasury:treasury-api")

include(":business")
include(":business:business-api")

include(":treasury-api-plugin")
include(":treasury-rest-api")
include(":economy-flyway")

// ChestShop: a single plugin module that shades straight to chestshop-<version>.jar. The
// old per-server-version adapter modules, the assemble module, and the former
// `:chestshop:plugin` nesting were folded into this one module when the core
// moved to a single modern (1.21.11) baseline.
include(":chestshop")
