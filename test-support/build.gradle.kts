// =============================================================================
// io.paradaux:test-support — a small, plugin-agnostic test-kit shared by the
// Paper plugins (treasury, business, chestshop). It is a *main*-scope library
// (not a test source set) so consumers depend on it with
// `testImplementation(project(":test-support"))` and get its two assertions plus
// the test dependencies they need to drive a startup test:
//
//   * HiberniaStartupAssertion — build the real Guice injector over a MockBukkit
//     server and assert CommandManager/ListenerManager registerAll() (route
//     conflicts / missing bindings surface here).
//   * MessageKeyAudit — bidirectional messages.properties ↔ source key audit.
//   * db.TestDatabaseExtension — the one MariaDB integration-test harness for
//     treasury, business and treasury-api-plugin (MariaDB4j locally / an external
//     server via <PREFIX>_TEST_JDBC_URL in CI, economy-flyway schema, per-test reset
//     derived from information_schema, ops/money-invariants.sql checked after
//     every test).
//   * properties.FixedSeedInCi — jqwik hook pinning the seed when CI=true.
//   * contract.TreasuryApiContract — executable spec of the TreasuryApi behaviour
//     consumers rely on; treasury runs it against the real implementation.
//
// The DB / jqwik / treasury-api pieces are compileOnly: only the modules that use
// them (and already declare them at test scope) pay for them.
//
// It depends only on the hibernia-framework artifact, Guice, JUnit 5, MockBukkit
// and the Paper API — never on any plugin. Those four are exposed as `api` so a
// consumer's test classpath inherits them (JUnit assertions, MockBukkit server,
// the framework types the assertions accept) without re-declaring each.
// =============================================================================

plugins {
    `java-library`
    id("io.paradaux.jvm-conventions")
}

description = "Hibernia shared test-kit (startup + message-key assertions)"

repositories {
    if (providers.gradleProperty("useMavenLocal").isPresent) {
        mavenLocal()
    }
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
    maven("https://repo.paradaux.io/releases")
    maven("https://repo.paradaux.io/snapshots")
}

dependencies {
    // The framework types the startup assertion accepts (Injector, CommandManager,
    // ListenerManager) and Guice itself — `api` so consumers building the injector
    // in their startup test see them without re-declaring.
    api(libs.hibernia.framework)
    api(libs.guice)

    // The Paper API (JavaPlugin/Listener/Server), provided at runtime by the server
    // in production but needed on the test classpath here and downstream.
    api(libs.paper.api)

    // JUnit 5 — the assertions throw AssertionFailedError; consumers write @Test.
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter)

    // MockBukkit — the in-memory server a consumer boots to host the plugin under
    // test. `api` so a consumer's startup test can MockBukkit.mock() without adding
    // the coordinate itself (matches chestshop's existing coordinate/version).
    api("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.116.3")

    // Shared MariaDB integration-test harness (io.paradaux.hibernia.testsupport.db).
    // compileOnly, not api: chestshop consumes this kit for its startup test only and
    // shouldn't inherit a database stack. The DB-backed consumers (treasury, business,
    // treasury-api-plugin) already declare these at test scope.
    compileOnly(libs.mariadb4j)
    compileOnly(libs.hikaricp)
    compileOnly(libs.flyway.core)
    // FixedSeedInCi (jqwik lifecycle hook); consumers with property tests declare jqwik.
    compileOnly(libs.jqwik)
    // contract.TreasuryApiContract — the implementing module supplies both at test scope.
    compileOnly(project(":treasury:treasury-api"))
    compileOnly(libs.assertj.core)
}

// Stage ops/money-invariants.sql into the jar so LedgerInvariants runs the exact
// check operators run against production — one source of truth, no copy to drift
// (same approach as the economy-flyway migrations staged onto each module's tests).
tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("ops/money-invariants.sql")) {
        into("io/paradaux/hibernia/testsupport/db")
    }
}
