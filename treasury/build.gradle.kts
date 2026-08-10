import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    jacoco
    id("com.gradleup.shadow")
    id("maven-publish")
    id("io.paradaux.paper-server-conventions")
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

    // SLF4J impl for tests so Lombok @Slf4j calls have a backing logger
    testRuntimeOnly(libs.slf4j.simple)

    // Integration tests build their schema by running the authoritative
    // economy-flyway migrations (staged onto the test classpath below), so the
    // tests and production share one source of schema truth — no bundled
    // schema.sql snapshot to drift. flyway-mysql handles the MySQL/MariaDB URL.
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.mysql)
}

// Stage the economy-flyway migrations onto the test classpath (under db/migration)
// so the IT harness can run them with Flyway (classpath:db/migration).
tasks.named<Copy>("processTestResources") {
    from(project(":economy-flyway").file("src/main/resources/db/migration")) {
        into("db/migration")
    }
}

tasks {
    test {
        finalizedBy(jacocoTestReport)
    }

    check {
        dependsOn(jacocoTestCoverageVerification)
    }

    jacocoTestCoverageVerification {
        dependsOn(jacocoTestReport)
        // Mirror the report's class-level exclusion so the rule applies to the
        // same in-scope set as the report.
        val excludes = listOf(
            "io/paradaux/treasury/Treasury.class",
            "io/paradaux/treasury/Treasury\$*.class",
            "io/paradaux/treasury/commands/**",
            "io/paradaux/treasury/adapters/VaultEconomyAdapter.class",
            "io/paradaux/treasury/adapters/VaultEconomyRegistrar.class",
            "io/paradaux/treasury/events/**",
            "io/paradaux/treasury/tasks/**",
            "io/paradaux/treasury/guice/**",
            "io/paradaux/treasury/utils/CallingPluginDetector.class",
            "io/paradaux/treasury/utils/LoggingConfigurer.class",
            "io/paradaux/treasury/mappers/**"
        )
        classDirectories.setFrom(
            files(classDirectories.files.map {
                fileTree(it) { exclude(excludes) }
            })
        )
        violationRules {
            rule {
                element = "BUNDLE"
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = "0.95".toBigDecimal()
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
        // Bukkit / I/O glue that can't be exercised without a running server.
        // Coverage targets apply to the in-scope set (services, api, utils, configs).
        val excludes = listOf(
            "io/paradaux/treasury/Treasury.class",
            "io/paradaux/treasury/Treasury\$*.class",
            "io/paradaux/treasury/commands/**",
            "io/paradaux/treasury/adapters/VaultEconomyAdapter.class",
            "io/paradaux/treasury/adapters/VaultEconomyRegistrar.class",
            "io/paradaux/treasury/events/**",
            "io/paradaux/treasury/tasks/**",
            "io/paradaux/treasury/guice/**",
            "io/paradaux/treasury/utils/CallingPluginDetector.class",
            "io/paradaux/treasury/utils/LoggingConfigurer.class",
            "io/paradaux/treasury/mappers/**"
        )
        classDirectories.setFrom(
            files(classDirectories.files.map {
                fileTree(it) { exclude(excludes) }
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

// The publish repository target (snapshot/release URL + REPO_USER/REPO_PASS creds)
// for treasury-api now lives in the io.paradaux.published-library-conventions
// plugin, applied by treasury/treasury-api itself (global/build/0004).
