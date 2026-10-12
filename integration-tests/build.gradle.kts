plugins {
    java
}

allprojects {
    group = "io.paradaux"
    version = providers.gradleProperty("version").orElse("2.0.0-SNAPSHOT").get()
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Wire test logging that's actually useful when tests fail.
        testLogging {
            events("passed", "skipped", "failed", "standardError")
            showStandardStreams = false
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
}
