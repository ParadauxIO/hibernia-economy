import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("com.gradleup.shadow")
    id("io.paradaux.paper-server-conventions")
}

// group + version are set centrally by the root allprojects block (single
// mono-repo version, 2.3.0-SNAPSHOT, overridable with -Pversion).
// The JVM toolchain, repositories, resource expansion, base test setup, shaded-jar
// defaults, and dev-server staging come from io.paradaux.paper-server-conventions.
description = "TreasuryAPI"

dependencies {
    implementation(project(":common"))

    // Paper API (provided by server)
    compileOnly(libs.paper.api)

    // Treasury API (provided at runtime by Treasury plugin)
    compileOnly(project(":treasury:treasury-api"))

    // Business API (provided at runtime by Business plugin)
    compileOnly(project(":business:business-api"))

    // LuckPerms API (optional softdepend — used by the group reconciliation cron)
    compileOnly(libs.luckperms.api)

    // Hibernia Framework
    implementation(libs.hibernia.framework)

    // Runtime impls
    implementation(libs.hikaricp)
    implementation(libs.mariadb.java.client)
    implementation(libs.reflections)
    implementation(libs.mybatis.core)
    implementation(libs.mybatis.guice)

    // Guice
    implementation(libs.guice)

    // JJWT (for JWT API key signing)
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    // Lombok
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    // Tests
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
    // GroupReconciliationTask extends BukkitRunnable and references the LuckPerms
    // API, so the test classpath needs both to load the class (even for pure tests).
    testImplementation(libs.paper.api)
    testImplementation(libs.luckperms.api)
    // The command handlers reference the Treasury/Business public APIs (compileOnly
    // in production — provided by the sibling plugins at runtime). Tests that exercise
    // those handlers need the API types on the test classpath.
    testImplementation(project(":treasury:treasury-api"))
    testImplementation(project(":business:business-api"))

    // Mapper integration tests run the mappers against a real MariaDB — MariaDB4j
    // unpacks a real MariaDB binary and runs it on a dynamic port (mirrors the
    // treasury/business harness). The schema is built from the authoritative
    // economy-flyway migrations (staged onto the test classpath below), so tests and
    // production share one source of schema truth — no schema.sql snapshot to drift.
    testImplementation(libs.assertj.core)
    testImplementation(libs.mariadb4j)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.mysql)
}

// Stage the economy-flyway migrations onto the test classpath (under db/migration)
// so the mapper-IT harness can run them with Flyway (classpath:db/migration).
tasks.named<Copy>("processTestResources") {
    from(project(":economy-flyway").file("src/main/resources/db/migration")) {
        into("db/migration")
    }
}

tasks {
    // Project-specific shaded-lib relocations. archiveClassifier + mergeServiceFiles
    // come from io.paradaux.paper-server-conventions.
    withType<ShadowJar> {
        val root = "io.paradaux.treasuryapi.libs"

        relocate("com.google.inject", "$root.guice")
        relocate("org.aopalliance",   "$root.org.aopalliance")
        relocate("org.mybatis",       "$root.mybatis")
        relocate("com.zaxxer.hikari", "$root.hikari")
        relocate("org.mariadb",       "$root.mariadb")
        relocate("org.reflections",   "$root.reflections")
        relocate("io.jsonwebtoken",   "$root.jjwt")
        relocate("com.fasterxml.jackson", "$root.jackson")
    }
}
