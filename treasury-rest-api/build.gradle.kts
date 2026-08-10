plugins {
    java
    jacoco
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("io.paradaux.jvm-conventions") // Java 21 toolchain + UTF-8 / release=21 JavaCompile
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
    implementation("org.mybatis.spring.boot:mybatis-spring-boot-starter:4.0.1")

    // Swagger / OpenAPI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")

    // Rate limiting — Bucket4j core + Caffeine (in-memory) and Lettuce
    // (Redis). Caffeine is the default; Redis kicks in when the
    // rate-limit.redis-host property is set (see RateLimitConfig).
    implementation("com.bucket4j:bucket4j_jdk17-core:8.19.0")
    implementation("com.bucket4j:bucket4j_jdk17-caffeine:8.19.0")
    implementation("com.bucket4j:bucket4j_jdk17-redis-common:8.19.0")
    implementation("com.bucket4j:bucket4j_jdk17-lettuce:8.19.0")
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
    testImplementation("org.mybatis.spring.boot:mybatis-spring-boot-starter-test:4.0.1")
    // Embedded MariaDB for market read-side integration tests (real MariaDB SQL:
    // ON DUPLICATE KEY, DATE_FORMAT, INTERVAL — H2 can't stand in).
    testImplementation(libs.mariadb4j)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test> {
    useJUnitPlatform()
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
                minimum = "0.72".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestReport, tasks.jacocoTestCoverageVerification)
}
