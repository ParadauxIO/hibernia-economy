plugins {
    java
}

description = "Integration tests for the DemocracyCraft economy stack — uses :mock-client to drive Paper plugins end-to-end."

dependencies {
    testImplementation(project(":mock-client"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")

    // Direct MariaDB access for state assertions — read balances, ledger
    // postings, firm rows, etc. Faster and more deterministic than scraping
    // chat output.
    testImplementation("org.mariadb.jdbc:mariadb-java-client:3.5.2")

    // JSON for Treasury REST API tests — parse response bodies into JsonNode.
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.12")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // Forward the connection-override -D properties to the forked test JVM
    // (Gradle doesn't propagate them automatically). Env-var fallbacks still work
    // since the fork inherits the environment.
    listOf(
        "mockclient.host", "mockclient.port",
        "devserver.host", "devserver.port",
        "devserver.db.url", "devserver.db.user", "devserver.db.password",
        "rcon.host", "rcon.port", "rcon.password",
        // Gates for tests that need the server booted with specific ChestShop config
        // (WorldGuard/GriefPrevention integration hooks, free shops) — startup-only flags.
        "chestshop.integrations", "chestshop.freeshops",
    ).forEach { key -> System.getProperty(key)?.let { systemProperty(key, it) } }
}
