// =============================================================================
// io.paradaux.jvm-conventions — the JVM baseline shared by every Paper plugin in
// the monorepo (treasury, business, treasury-api-plugin, chestshop): the Java
// toolchain and compiler options. Repositories, shading, and staging live in the
// narrower io.paradaux.paper-server-conventions (the three shading server
// plugins); ChestShop applies only this base and keeps its bespoke repos/shadow.
// The libraries (common, test-support, the *-api jars) and treasury-rest-api apply
// it too, which is how io.paradaux.static-analysis-conventions reaches them all.
// =============================================================================

plugins {
    java
    // Error Prone + NullAway + Spotless ride along with the JVM baseline so every
    // JVM project gets them without touching its own build file.
    id("io.paradaux.static-analysis-conventions")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}
