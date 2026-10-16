import java.util.concurrent.ConcurrentHashMap

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

    // Timing visibility: print the slowest classes and tests when the run ends and write
    // every class's and test's duration, slowest first, to build/reports/test-timings.txt
    // (CI can upload it). Durations come from Gradle's own start/end timestamps.
    val timingsFile = layout.buildDirectory.file("reports/test-timings.txt")
    val classTimes = ConcurrentHashMap<String, Long>()
    val testTimes = ConcurrentHashMap<String, Long>()
    doFirst {
        classTimes.clear()
        testTimes.clear()
    }
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun beforeTest(testDescriptor: TestDescriptor) {}

        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
            val owner = testDescriptor.className?.substringAfterLast('.') ?: "?"
            testTimes["$owner.${testDescriptor.name}"] = result.endTime - result.startTime
        }

        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            val className = suite.className
            if (className != null) {
                classTimes[className.substringAfterLast('.')] = result.endTime - result.startTime
            }
            if (suite.parent != null || classTimes.isEmpty()) return
            fun fmt(ms: Long) = "%7.1fs".format(ms / 1000.0)
            val classes = classTimes.entries.sortedByDescending { it.value }
            val tests = testTimes.entries.sortedByDescending { it.value }
            val total = result.endTime - result.startTime
            val file = timingsFile.get().asFile
            file.parentFile.mkdirs()
            file.writeText(buildString {
                appendLine("# ${classes.size} classes, ${tests.size} tests, wall ${fmt(total).trim()}")
                appendLine("## classes (slowest first)")
                classes.forEach { appendLine("${fmt(it.value)}  ${it.key}") }
                appendLine("## tests (slowest first)")
                tests.forEach { appendLine("${fmt(it.value)}  ${it.key}") }
            })
            println("\nSlowest test classes (${classes.size} classes, wall ${fmt(total).trim()}):")
            classes.take(10).forEach { println("${fmt(it.value)}  ${it.key}") }
            println("Slowest tests:")
            tests.take(10).forEach { println("${fmt(it.value)}  ${it.key}") }
            println("All timings: ${file.relativeTo(rootDir)}")
        }
    })
}
