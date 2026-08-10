// flyway-mysql + the JDBC driver are added to the *plugin* classpath so the
// Flyway plugin's DatabaseType service-loader can find them. The
// `flyway.configurations` mechanism (a separate task-time configuration)
// resolves dependencies fine but doesn't expose them to the plugin's own
// classloader on this version, which is why MariaDB/MySQL-handling fails
// with "No database found to handle jdbc:mysql://...".
buildscript {
    repositories { mavenCentral() }
    dependencies {
        classpath("org.flywaydb:flyway-mysql:12.9.0")
        classpath("com.mysql:mysql-connector-j:9.4.0")
    }
}

plugins {
    java
    // Declared WITH its version here (not centralized in the root build) on
    // purpose: the plugin must load on the same buildscript classloader as the
    // flyway-mysql + JDBC driver added in the buildscript {} block above, or its
    // DatabaseType service-loader can't see them ("No database found to handle
    // jdbc:mysql://…"). Centralizing it via the root `apply false` splits the two
    // onto different classloaders and breaks MySQL/MariaDB handling.
    //
    // Pinned to 12.9.0 to match the flyway library catalog (PAR-273): the 10.22.0
    // plugin referenced org.gradle.api.plugins.JavaPluginConvention, removed in
    // Gradle 9, so flywayMigrate failed under Gradle 9.6 (ADT — CI fix).
    id("org.flywaydb.flyway") version "12.9.0"
}

// group + version are set centrally by the root allprojects block (single
// mono-repo version, 2.3.0-SNAPSHOT, overridable with -Pversion).
description = "Flyway-managed schema for the shared DemocracyCraft economy database"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

// (No `flywayMigration` configuration — see the buildscript block above for
// why driver and vendor module live on the plugin classpath instead.)

// =====================================================================
// Configuration sources, in priority order:
//   1. Gradle -P properties     (e.g. -PflywayUrl=...)
//   2. Environment variables    (FLYWAY_URL, FLYWAY_USER, FLYWAY_PASSWORD)
//   3. Defaults: localhost/economy with empty credentials
//
// Treat the env-var path as the production path: CI / Docker / k8s should
// inject FLYWAY_URL / FLYWAY_USER / FLYWAY_PASSWORD. The Gradle -P path is
// for ad-hoc local invocation.
// =====================================================================

fun resolve(envName: String, propName: String, default: String): String =
    (project.findProperty(propName) as String?)
        ?: System.getenv(envName)
        ?: default

flyway {
    url = resolve("FLYWAY_URL", "flywayUrl", "jdbc:mysql://localhost:3306/economy")
    user = resolve("FLYWAY_USER", "flywayUser", "root")
    password = resolve("FLYWAY_PASSWORD", "flywayPassword", "")
    schemas = arrayOf(resolve("FLYWAY_SCHEMA", "flywaySchema", "economy"))
    locations = arrayOf("filesystem:src/main/resources/db/migration")

    // We expect a green-field database. Pass -Pbaseline=true on the first run
    // against a pre-existing DB so Flyway records a baseline at version 0
    // instead of trying to apply V1 against a populated schema.
    baselineOnMigrate = (project.findProperty("baseline") as String?) == "true"
    baselineVersion = "0"

    // Validate that on-disk migration files match what's in flyway_schema_history.
    // Production deploys should always have this on; loosen only for ad-hoc dev.
    validateOnMigrate = true

    // Default name; explicit so it's discoverable from the build file.
    table = "flyway_schema_history"

    // Flyway 10 defaults cleanDisabled=true to prevent accidental wipes in
    // production. Allow opt-in via -PflywayCleanEnabled=true so dev hosts can
    // reset (e.g. when V1 evolves before any prod deploy). Production CI must
    // not set this property.
    cleanDisabled = (project.findProperty("flywayCleanEnabled") as String?) != "true"
}

// Refuse to run a real Flyway task against the unconfigured root/empty-password
// default. This is a TASK-time guard (doFirst), NOT a configuration-time throw:
// it never breaks `./gradlew build` for the rest of the monorepo, and only trips
// when someone actually invokes a connecting Flyway task without injecting
// credentials — so a bare `flywayMigrate` can't silently connect as root with no
// password to whatever sits on localhost:3306 (ADT-148). CI / Docker / k8s set
// FLYWAY_URL / FLYWAY_USER / FLYWAY_PASSWORD; ad-hoc local runs pass the -P forms.
fun explicitlySet(envName: String, propName: String): Boolean =
    project.findProperty(propName) != null || System.getenv(envName) != null

val flywayCredsConfigured = explicitlySet("FLYWAY_URL", "flywayUrl") &&
    explicitlySet("FLYWAY_USER", "flywayUser") &&
    explicitlySet("FLYWAY_PASSWORD", "flywayPassword")

listOf("flywayMigrate", "flywayClean", "flywayValidate", "flywayInfo",
       "flywayBaseline", "flywayRepair", "flywayUndo").forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach {
        doFirst {
            if (!flywayCredsConfigured) {
                throw GradleException(
                    "Refusing to run $taskName with the unconfigured default credentials " +
                    "(root / empty password / localhost). Inject FLYWAY_URL, FLYWAY_USER and " +
                    "FLYWAY_PASSWORD as environment variables, or pass -PflywayUrl / -PflywayUser / " +
                    "-PflywayPassword (an explicit empty password is fine). See the configuration " +
                    "block above in economy-flyway/build.gradle.kts.")
            }
        }
    }
}
