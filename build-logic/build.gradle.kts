plugins {
    `kotlin-dsl`
}

// Compile the convention plugins themselves on JDK 21 (the monorepo toolchain).
// Without this, Gradle's toolchain auto-detection can pick a stray JRE that has
// no compiler and the included build fails to configure.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // Needed so the paper-server convention plugin can reference the ShadowJar
    // task type and configure it. The version comes from the shared version
    // catalog (gradle/libs.versions.toml, shadow = "…"), the same entry the root
    // build applies via `alias(libs.plugins.shadow)`, so the two never drift.
    implementation(libs.shadow.lib)
}
