import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    jacoco
    id("com.gradleup.shadow")
    id("maven-publish")
    id("io.paradaux.paper-server-conventions")
    // TestServicesModule/TestConfigs (the real-service Guice wiring the ITs use) live in
    // src/testFixtures so business can run its ITs against the REAL TreasuryApi.
    // treasury itself is never published (only treasury-api is), so the fixtures
    // variant never reaches a repository.
    `java-test-fixtures`
    id("io.paradaux.mutation-testing-conventions")
}

// group + version are set centrally by the root allprojects block (single
// mono-repo version, 2.3.0-SNAPSHOT, overridable with -Pversion).
// The JVM toolchain, repositories, resource expansion, base test setup, shaded-jar
// defaults, and dev-server staging come from io.paradaux.paper-server-conventions.
description = "Treasury"

dependencies {
    // Paper API (provided by server)
    compileOnly(libs.paper.api)

    // Vault API, exclude Bukkit to avoid capability conflict with Paper
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }

    // LuckPerms API (optional softdepend)
    compileOnly(libs.luckperms.api)

    // log4j-core: provided by Paper at runtime; needed at compile time
    // to programmatically adjust log levels for our package hierarchy.
    compileOnly(libs.log4j.core)

    // Treasury API submodule
    implementation(project(":treasury:treasury-api"))
    implementation(project(":common"))

    // Hibernia Framework
    implementation(libs.hibernia.framework)

    // Runtime impls
    implementation(libs.hikaricp)
    implementation(libs.mariadb.java.client)
    implementation(libs.reflections)
    implementation(libs.mybatis.core)
    implementation(libs.mybatis.guice)

    // Guice + AOP. mybatis-guice's @Transactional uses AOP interception,
    // so we ship the standard guice jar (which bundles cglib for proxies).
    implementation(libs.guice)

    // Lombok
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    // ---- Test dependencies ----
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Shared startup + message-key test-kit. Brings JUnit, Guice, the framework and
    // MockBukkit transitively (declared `api` there) so the startup test can boot an
    // in-memory server and drive the real injector without re-declaring them.
    testImplementation(project(":test-support"))

    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)

    // Property-based tests (jqwik runs as a second JUnit Platform engine).
    testImplementation(libs.jqwik)

    // Embedded MariaDB for integration tests — no Docker required.
    // MariaDB4j unpacks a real MariaDB binary into a temp dir and runs it on a
    // dynamic port.
    testImplementation(libs.mariadb4j)

    // Wiring used by services in tests (impls; production scope is `implementation`)
    testImplementation(libs.hikaricp)
    testImplementation(libs.mariadb.java.client)
    testImplementation(libs.mybatis.core)
    testImplementation(libs.mybatis.guice)
    testImplementation(libs.guice)

    // LuckPerms is a compileOnly soft-dependency in production. MembershipServiceImpl
    // declares an `@Inject(optional = true)` setter that takes a LuckPerms parameter, so
    // Guice needs the class on the classpath at injection time even when no binding exists.
    // Tests also mock LuckPerms to cover the group-aware membership paths.
    testImplementation(libs.luckperms.api)

    // Paper API at test scope so Mockito can mock FileConfiguration / OfflinePlayer /
    // Player when testing classes that read config from a Bukkit plugin.
    testImplementation(libs.paper.api)

    // Vault API at test scope (compileOnly in production) so VaultEconomyAdapterTest
    // can drive the adapter and read its EconomyResponse.
    testImplementation("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }

    // SLF4J impl for tests so Lombok @Slf4j calls have a backing logger
    testRuntimeOnly(libs.slf4j.simple)

    // Integration tests build their schema by running the authoritative
    // economy-flyway migrations (staged onto the test classpath below), so the
    // tests and production share one source of schema truth — no bundled
    // schema.sql snapshot to drift. flyway-mysql handles the MySQL/MariaDB URL.
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.mysql)

    // Test fixtures: compile against the same API/Guice the services use.
    testFixturesImplementation(project(":treasury:treasury-api"))
    testFixturesImplementation(libs.guice)
    testFixturesImplementation(libs.hibernia.framework)
}

// Stage the economy-flyway migrations onto the test classpath (under db/migration)
// so the IT harness can run them with Flyway (classpath:db/migration).
tasks.named<Copy>("processTestResources") {
    from(project(":economy-flyway").file("src/main/resources/db/migration")) {
        into("db/migration")
    }
}

// The paper-server conventions disable :jar so it can't overwrite the shaded plugin
// jar. Re-enable it under a "thin" classifier (no clash with the shadow jar) so a
// testFixtures(project(":treasury")) dependency resolves to the plain, unrelocated
// classes — business's ITs run against the real TreasuryApi through it. Only the
// shadow jar is staged or shipped.
tasks.named<Jar>("jar") {
    enabled = true
    archiveClassifier.set("thin")
}

// Bukkit / I/O glue that can't be exercised without a running server — the
// plugin main, command and listener entrypoints, the scheduler tasks, the Guice
// wiring and the MyBatis mapper interfaces. Logic does not belong in these: the
// tax-cycle calendar maths moved out of tasks/TaxCycleTask into
// utils/TaxCycleSchedule and the command amount rules into :common AmountInput,
// both of which are measured. VaultEconomyAdapter is no longer excluded: it is
// every Vault plugin's way into the economy and is unit-tested. Shared by the
// report and the verification rule so the gate evaluates exactly the set the
// report shows.
val coverageExcludes = listOf(
    "io/paradaux/treasury/Treasury.class",
    "io/paradaux/treasury/Treasury\$*.class",
    "io/paradaux/treasury/commands/**",
    "io/paradaux/treasury/adapters/VaultEconomyRegistrar.class",
    "io/paradaux/treasury/events/**",
    "io/paradaux/treasury/tasks/**",
    "io/paradaux/treasury/guice/**",
    "io/paradaux/treasury/utils/CallingPluginDetector.class",
    "io/paradaux/treasury/utils/LoggingConfigurer.class",
    "io/paradaux/treasury/mappers/**",
)

tasks {
    test {
        finalizedBy(jacocoTestReport)
    }

    check {
        dependsOn(jacocoTestCoverageVerification)
    }

    jacocoTestCoverageVerification {
        dependsOn(jacocoTestReport)
        // Same in-scope set as the report (coverageExcludes, defined once above).
        classDirectories.setFrom(
            files(classDirectories.files.map {
                fileTree(it) { exclude(coverageExcludes) }
            })
        )
        // A ratchet, not a target: a few points under the measured coverage so a
        // change that drops tests fails, without pushing anyone to write tests
        // that only execute lines. Whether those tests would catch a bug is
        // measured by mutation testing (`pitest`, see below), not by this gate.
        // Measured 2026-10 (VaultEconomyAdapter now in scope, Lombok-generated code
        // filtered): LINE 96.0%, BRANCH 86.5%. Was a LINE-only 0.95 gate.
        violationRules {
            rule {
                element = "BUNDLE"
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = "0.93".toBigDecimal()
                }
                limit {
                    counter = "BRANCH"
                    value = "COVEREDRATIO"
                    minimum = "0.83".toBigDecimal()
                }
            }
        }
    }

    jacocoTestReport {
        dependsOn(test, classes)
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
        classDirectories.setFrom(
            files(classDirectories.files.map {
                fileTree(it) { exclude(coverageExcludes) }
            })
        )
    }

    // Project-specific shaded-lib relocations. archiveClassifier + mergeServiceFiles
    // come from io.paradaux.paper-server-conventions.
    withType<ShadowJar> {
        relocate("com.google.inject", "io.paradaux.libs.guice")
        relocate("javax.inject", "io.paradaux.libs.javax")
        relocate("org.aopalliance", "io.paradaux.libs.aopalliance")
        relocate("io.jsonwebtoken", "io.paradaux.libs.jjwt")
        relocate("com.fasterxml.jackson", "io.paradaux.libs.jackson")
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

// Mutation testing (`./gradlew :treasury:pitest`; not part of `check`). Scoped to
// the money logic: the ledger/account/gov/salary/tax services and caches, the
// public API impls (Treasury/Tax/Market), the Vault adapter's money paths and the
// Money/dedup utilities — never the command/listener/Guice glue.
pitest {
    targetClasses.set(listOf(
        "io.paradaux.treasury.services.impl.*",
        "io.paradaux.treasury.services.cache.*",
        "io.paradaux.treasury.api.impl.*",
        "io.paradaux.treasury.adapters.VaultEconomyAdapter*",
        "io.paradaux.treasury.utils.*",
    ))
    excludedClasses.set(listOf(
        // Bukkit/log4j glue (also outside the jacoco gate).
        "io.paradaux.treasury.utils.CallingPluginDetector*",
        "io.paradaux.treasury.utils.LoggingConfigurer*",
    ))
    // DB ITs run under PIT and share one schema: one minion at a time (see the
    // convention plugin's header).
    threads.set(1)
    // Ratchet a couple of points under the measured scores (2026-10, unit + DB
    // ITs: 77.5% mutation score — 69.2% before the ledger/tax/Vault tests — and
    // 95% line coverage of the mutated classes). Raise as survivors are killed.
    mutationThreshold.set(75)
    coverageThreshold.set(92)
}

// The publish repository target (snapshot/release URL + REPO_USER/REPO_PASS creds)
// for treasury-api now lives in the io.paradaux.published-library-conventions
// plugin, applied by treasury/treasury-api itself (global/build/0004).
