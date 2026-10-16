// :common — a tiny pure-JVM library shared across the build graph (ADT-22).
// Holds framework-free utilities duplicated 3-4× before: UUID ↔ BINARY(16)
// conversion and the JWT signing-key derivation that the mint side
// (treasury-api-plugin) and the verify side (treasury-rest-api) must agree on.
// No Paper/Spring/MyBatis deps — anyone can depend on it, including the Spring
// treasury-rest-api (its first project() dependency).

plugins {
    `java-library`
    id("io.paradaux.jvm-conventions")
    id("io.paradaux.mutation-testing-conventions")
}

group = "io.paradaux"
version = rootProject.version

repositories {
    mavenCentral()
}

dependencies {
    // compileOnly so :common does not drag HikariCP onto the Spring REST API's
    // classpath; the Paper plugins that use DataSourceProvider already bundle Hikari
    // (ADT-184).
    compileOnly(libs.hikaricp)
    testImplementation(libs.hikaricp)
    // @Nullable for NullAway (common is null-checked; see static-analysis-conventions).
    // Class-retention annotations only, so compileOnly like the published *-api jars.
    compileOnly(libs.jetbrains.annotations)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// Mutation testing (`./gradlew :common:pitest`; not part of `check`). Everything
// here is logic shared by the money paths — the command amount rules, the
// balance-tax brackets, the overdraft policy, the JWT key derivation both sides
// must agree on.
pitest {
    targetClasses.set(listOf("io.paradaux.common.*"))
    // HikariCP pool wiring: building a pool needs a live MariaDB (Hikari 7 seeds
    // it eagerly), so its setters are only exercised by the plugins' DB ITs.
    excludedClasses.set(listOf("io.paradaux.common.DataSourceProvider*"))
    // Ratchet a couple of points under the measured scores (2026-10: 97.8%
    // mutation score, 92% line coverage of the mutated classes).
    mutationThreshold.set(95)
    coverageThreshold.set(89)
}
