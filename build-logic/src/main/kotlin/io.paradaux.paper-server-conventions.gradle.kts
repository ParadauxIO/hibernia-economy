import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.language.jvm.tasks.ProcessResources

// =============================================================================
// io.paradaux.paper-server-conventions — the build shared, byte-for-byte, by the
// three shading Paper *server* plugins (treasury, business, treasury-api-plugin):
// the JVM baseline (via jvm-conventions), the common repositories, plugin.yml /
// messages resource expansion, the base test setup, and the shaded-jar + staging
// wiring. Each project still owns what genuinely differs: its relocations, its
// dependencies, and (treasury/business) its jacoco coverage gate.
//
// ChestShop does NOT apply this — its repos/shadow/resources diverge too much to
// share safely; it applies only io.paradaux.jvm-conventions.
// =============================================================================

plugins {
    id("io.paradaux.jvm-conventions")
}

// Mirror the Maven default goal locally.
defaultTasks("clean", "shadowJar")

repositories {
    // mavenLocal is opt-in (-PuseMavenLocal) so normal/CI builds resolve
    // hibernia-framework only from the declared remotes — reproducible, never a
    // stale local artifact. Pass the flag (added first) when iterating on the
    // hibernia-framework submodule locally. (PAR-267)
    if (providers.gradleProperty("useMavenLocal").isPresent) {
        mavenLocal()
    }
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven("https://oss.sonatype.org/content/groups/public/")
    maven("https://jitpack.io")
    maven {
        name = "ParadauxReleases"
        url = uri("https://repo.paradaux.io/releases")
        mavenContent { releasesOnly() }
    }
    maven {
        name = "ParadauxSnapshots"
        url = uri("https://repo.paradaux.io/snapshots")
        mavenContent { snapshotsOnly() }
    }
}

// Keep resource filtering tight to avoid $ expansion issues in YAML like config.yml.
tasks.named<ProcessResources>("processResources") {
    filteringCharset = "UTF-8"
    // Capture at configuration time so the filesMatching action never touches
    // `project` at execution time (config-cache safe; Gradle 10 forward-compat).
    val expansions = mapOf(
        "version" to project.version,
        "name" to project.name,
        "description" to (project.description ?: ""),
    )
    filesMatching(listOf("**/*.properties", "plugin.yml", "paper-plugin.yml", "application*.yml")) {
        // Expands ${...} from these project properties only in these files.
        expand(expansions)
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // Tag-based filtering: gradle test -PskipIT skips DB-backed integration tests.
    if (project.hasProperty("skipIT")) {
        useJUnitPlatform { excludeTags("integration") }
    }
    testLogging {
        events("failed", "skipped")
        showStandardStreams = false
        exceptionFormat = TestExceptionFormat.FULL
    }
}

// Shaded-jar + dev-server staging. Deferred until the shadow plugin is applied by
// the consuming project, so this convention is robust to plugin-application order.
plugins.withId("com.gradleup.shadow") {
    // Shadow 9 writes shadowJar to build/libs/<name>.jar by default, and so does
    // :jar — which runs *after* :shadowJar and would overwrite the fat jar with a
    // dependency-less thin one that disables itself on enable. Disable :jar.
    tasks.named<Jar>("jar") { enabled = false }

    // Produce a single shaded jar without the "-all" classifier. Relocations are
    // project-specific and stay in each build's own withType<ShadowJar> block.
    tasks.withType<ShadowJar>().configureEach {
        archiveClassifier.set("")
        mergeServiceFiles()
    }

    // In-repo dev server staging dir (server/plugins at the repo root). A top-level
    // subproject is one level under the root, so it's "../server/plugins". (PAR-268)
    val pluginsDir = layout.projectDirectory.dir("../server/plugins")
    val copyPlugin = tasks.register<Copy>("copyPlugin") {
        val shadowJar = tasks.named<ShadowJar>("shadowJar")
        dependsOn(shadowJar)
        from(shadowJar.flatMap { it.archiveFile })
        into(pluginsDir)
        onlyIf { !project.hasProperty("ci") } // don't run on CI
        doFirst {
            // Remove this plugin's previously-staged jars (any version) so stale
            // copies don't pile up — the dev server would otherwise load two
            // versions of the same plugin. Scoped to THIS artifact's base name + a
            // version digit, so it never matches a sibling (treasury must not match
            // treasury-api-plugin), a data folder, or another server jar. (PAR-268)
            val re = Regex("^${Regex.escape(shadowJar.get().archiveBaseName.get())}-\\d.*\\.jar$")
            pluginsDir.asFile.listFiles { f -> f.isFile && re.matches(f.name) }?.forEach { it.delete() }
        }
    }
    tasks.named<ShadowJar>("shadowJar") { finalizedBy(copyPlugin) }
}
