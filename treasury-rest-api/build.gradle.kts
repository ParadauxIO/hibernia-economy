plugins {
    java
    jacoco
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("io.paradaux.jvm-conventions") // Java 25 toolchain + UTF-8 / release=25 JavaCompile
}

// group + version are set centrally by the root allprojects block (single
// mono-repo version, 2.3.0-SNAPSHOT, overridable with -Pversion).
description = "treasury-rest-api"

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    // Caching for the public ChestShop market endpoints (Caffeine, in-process).
    // Collapses identical heavy-aggregate reads to one query per short TTL.
    implementation("org.springframework.boot:spring-boot-starter-cache")
    // Pinned MyBatis starter 4.0.1: verified compile+test-green against this module's
    // Spring Boot 4.1.0 GA target (global/dependencies/0003). A Spring-Boot-4-aligned
    // MyBatis starter bump should be evaluated when network/catalog access is available.
    implementation("org.mybatis.spring.boot:mybatis-spring-boot-starter:4.1.0")

    // Swagger / OpenAPI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")

    // Rate limiting — Bucket4j core + Caffeine (in-memory) and Lettuce
    // (Redis). Caffeine is the default; Redis kicks in when the
    // rate-limit.redis-host property is set (see RateLimitConfig).
    implementation("com.bucket4j:bucket4j_jdk17-core:8.21.0")
    implementation("com.bucket4j:bucket4j_jdk17-caffeine:8.21.0")
    implementation("com.bucket4j:bucket4j_jdk17-redis-common:8.21.0")
    implementation("com.bucket4j:bucket4j_jdk17-lettuce:8.21.0")
    // Lettuce and Caffeine versions are both left to the Spring Boot BOM
    // (lettuce 7.x, caffeine 3.2.x). bucket4j_jdk17-lettuce declares lettuce 6.x
    // as a `provided` compile floor, but every lettuce symbol it actually calls
    // (RedisClient.connect, StatefulRedisConnection.async, RedisAsyncCommands
    // eval/del/get, ScriptOutputType, RedisException, RedisFuture) is unchanged
    // in lettuce 7 — verified by bytecode linkage — so the BOM version is safe.
    implementation("io.lettuce:lettuce-core")
    implementation("com.github.ben-manes.caffeine:caffeine")

    // JWT
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    compileOnly("org.projectlombok:lombok")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.mybatis.spring.boot:mybatis-spring-boot-starter-test:4.1.0")
    // Embedded MariaDB for market read-side integration tests (real MariaDB SQL:
    // ON DUPLICATE KEY, DATE_FORMAT, INTERVAL — H2 can't stand in).
    testImplementation(libs.mariadb4j)
    // The IT harness builds its schema by running the authoritative economy-flyway
    // migrations (staged onto the test classpath below) instead of a hand-written
    // stand-in, so tests see the real FKs, NOT NULLs and the trg_postings_* balance
    // triggers — same approach as Treasury (PAR-239) and Business (PAR-242).
    // Plain flyway-core, not spring-boot-flyway: no auto-configuration, the harness
    // migrates exactly once per JVM before the first Spring context boots.
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.mysql)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Stage the economy-flyway migrations onto the test classpath (under db/migration)
// so EmbeddedMariaDb can run them with Flyway (classpath:db/migration).
tasks.named<Copy>("processTestResources") {
    from(project(":economy-flyway").file("src/main/resources/db/migration")) {
        into("db/migration")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()

    // OpenApiSnapshotIT compares the runtime /v3/api-docs against the committed
    // openapi/openapi.json. Absolute paths so IDE and Gradle runs agree, and the
    // snapshot is a declared input so editing it re-runs the test instead of
    // reporting a stale UP-TO-DATE pass.
    val snapshot = layout.projectDirectory.file("openapi/openapi.json")
    systemProperty("openapi.snapshot", snapshot.asFile.path)
    systemProperty("openapi.actual", layout.buildDirectory.file("openapi/openapi.json").get().asFile.path)
    inputs.files(snapshot).withPropertyName("openApiSnapshot").withPathSensitivity(PathSensitivity.RELATIVE)
    // `-Dopenapi.update=true` on the Gradle command line rewrites the snapshot. The
    // forked test JVM doesn't inherit the build's -D flags, so forward it explicitly.
    providers.systemProperty("openapi.update").orNull?.let { systemProperty("openapi.update", it) }
}

// ADT-50: the money service had no coverage gate. Report on every test run and
// enforce a regression floor wired into `check`. The floor is a ratchet set just
// below current coverage — raise it as coverage improves, never let it slip.
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                // Measured 82.5% line coverage (1514/1834) after the route-table,
                // filter and real-schema tests; floor sits ~2.5 points below.
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestReport, tasks.jacocoTestCoverageVerification)
}
