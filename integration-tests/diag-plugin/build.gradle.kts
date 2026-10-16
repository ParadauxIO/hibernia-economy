plugins {
    java
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // WorldGuard/WorldEdit API from EngineHub (proper compile jars); GriefPrevention
    // from its server plugin jar (its API compiles cleanly). Used by the diag commands
    // that create regions/claims for the protection-integration tests.
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.19")
    // No published API artifact: point -PgriefPreventionJar=/path/to/GriefPrevention.jar
    // (or GRIEFPREVENTION_JAR) at the plugin jar. ops/integration/run.sh downloads one
    // and passes it.
    compileOnly(files(
        providers.gradleProperty("griefPreventionJar")
            .orElse(providers.environmentVariable("GRIEFPREVENTION_JAR"))
    ))
}

tasks.jar {
    archiveBaseName.set("DcDiag")
    archiveVersion.set("")
    archiveClassifier.set("")
}
