import info.solidsoft.gradle.pitest.PitestPluginExtension

// =============================================================================
// io.paradaux.mutation-testing-conventions — PIT mutation testing for the
// money-handling modules (treasury, business, common, treasury-api-plugin,
// chestshop).
//
// Line coverage only says a line ran; mutation testing says a test would have
// FAILED had that line been wrong (a flipped `<`, a dropped negate, a rounding
// mode swapped). That is the quality signal the coverage gates used to stand in
// for, so the jacoco gates were lowered to a ratchet and this is the gate that
// matters for logic.
//
// Deliberately NOT wired into `check`: a full run is minutes per module, too slow
// for every local build. It is the plugin's standard `pitest` task, run by CI as
//   * a PR job — incremental, reusing the history file below (only mutants in
//     changed classes / covered by changed tests are re-analysed), and
//   * a nightly job — full (delete/skip the restored history first).
//
// Each module sets only what genuinely differs: `targetClasses` (its logic
// packages — never commands/listeners/guice glue, which are excluded from jacoco
// too), and its `mutationThreshold` / `coverageThreshold` ratchet, set a couple of
// points under the measured baseline. Raise them as surviving mutants are killed.
//
// DB-backed integration tests (@Tag("integration")) run under PIT in treasury,
// where most of the ledger/tax money logic is only exercised against real MariaDB
// (unit-only it scores ~43%; with ITs ~69%). That needs TREASURY_TEST_JDBC_URL
// (like `test` on CI) and `threads = 1` there: every PIT minion is a separate JVM
// pointed at the SAME schema and the harness truncates tables before each test,
// so parallel minions would wipe each other's fixtures and "kill" mutants by
// interference. 4 threads again needs a per-JVM database in the test harness.
// business / treasury-api-plugin exclude their ITs for now (see their builds).
// =============================================================================

plugins {
    java
    id("info.solidsoft.pitest")
}

// Precompiled script plugins can't use the generated `libs` accessors; read the
// shared catalog (gradle/libs.versions.toml) the consuming build already has.
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
fun catalogVersion(alias: String): String = catalog.findVersion(alias).get().requiredVersion

configure<PitestPluginExtension> {
    pitestVersion.set(catalogVersion("pitest"))
    // Runs the JUnit Platform (Jupiter 6) tests. The plugin's launcher dependency
    // is `provided`; gradle-pitest-plugin adds the junit-platform-launcher that
    // matches the module's own platform version, so Platform 6 is used as-is.
    junit5PluginVersion.set(catalogVersion("pitest-junit5"))

    // STRONGER = DEFAULTS + REMOVE_CONDITIONALS_EQUAL_ELSE + EXPERIMENTAL_SWITCH:
    // the extra operators are the ones that model "the guard was skipped" and
    // "the wrong case ran", i.e. the classic money-logic bugs.
    // Run EVERY test against the mutants, not just tests whose package matches
    // targetClasses (the plugin's default): e.g. treasury's BalanceTaxServiceIT
    // lives in services/, its subject in services/impl/, and under the default it
    // never ran — the class scored 0% despite being well tested. Matched by the
    // repo's test naming (*Test / *IT, plus @Nested inner classes) rather than a
    // bare package glob: PIT also scans main classes, and loading e.g. a
    // WorldEdit listener as a "test" fails the green-suite precheck.
    targetTests.set(listOf("io.paradaux.*Test", "io.paradaux.*Test$*", "io.paradaux.*IT", "io.paradaux.*IT$*"))
    mutators.set(listOf("STRONGER"))
    threads.set(4)
    // Minions are plain JVMs, not Gradle test workers, so they don't inherit the
    // modules' test jvmArgs: Mockito's inline mock maker self-attaches its agent.
    jvmArgs.set(listOf("-XX:+EnableDynamicAgentLoading"))
    outputFormats.set(listOf("XML", "HTML"))
    // Stable report path (build/reports/pitest/index.html, mutations.xml) so CI
    // can upload it as an artifact without globbing a timestamp directory.
    timestampedReports.set(false)
    // toString/hashCode (mostly Lombok-generated) produce mutants no behavioural
    // test should be expected to pin. Log calls (slf4j, JUL, …) are already
    // skipped by PIT's default avoidCallsTo list.
    excludedMethods.set(listOf("toString", "hashCode"))
    // Generous constant: the DB-backed integration tests (where much of treasury's
    // money logic actually runs) are slower than unit tests and a timed-out mutant
    // is counted as killed anyway, so a tight timeout would only add noise.
    timeoutConstInMillis.set(10_000)

    // Incremental analysis: PIT records per-mutant results keyed on class + test
    // bytecode hashes; on the next run it reuses every result whose class and
    // covering tests are unchanged. One file per module under build/ — CI caches
    // `*/build/pitest-history/` between PR runs (see the CI note in the PR).
    // Reading a stale or missing history is safe: PIT just re-analyses.
    val history = layout.buildDirectory.file("pitest-history/history.bin")
    historyInputLocation.set(history)
    historyOutputLocation.set(history)
}

dependencies {
    // Since PIT 1.23 the history store above is a plugin, not core: without it
    // PIT aborts with "History has been enabled but no history plugin has been
    // installed". It self-registers (feature `default_history`, on by default).
    "pitest"(catalog.findLibrary("pitest-history-plugin").get())
}
