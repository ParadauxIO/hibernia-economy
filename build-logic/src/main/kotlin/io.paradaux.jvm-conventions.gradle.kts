// =============================================================================
// io.paradaux.jvm-conventions — the JVM baseline shared by every Paper plugin in
// the monorepo (treasury, business, treasury-api-plugin, chestshop): the Java
// toolchain and compiler options. Repositories, shading, and staging live in the
// narrower io.paradaux.paper-server-conventions (the three shading server
// plugins); ChestShop applies only this base and keeps its bespoke repos/shadow.
// =============================================================================

plugins {
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}
