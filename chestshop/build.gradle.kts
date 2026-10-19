
// =============================================================================
// ChestShop — a single Paper plugin module, compiled against the Paper 1.21.11
// API and shaded straight to chestshop-<version>.jar. The former per-server-version
// adapter modules + the assemble module (and the old `:chestshop:plugin`
// nesting) were folded into this one module once the core adopted a single
// modern baseline (PAR-258). group + version are inherited from the root
// allprojects block (the unified monorepo version).
// =============================================================================

plugins {
    `java-library`
    jacoco
    id("com.gradleup.shadow")
    id("io.paradaux.jvm-conventions")
    id("io.paradaux.mutation-testing-conventions")
}

// The Java toolchain (21) and JavaCompile encoding/release come from
// io.paradaux.jvm-conventions. ChestShop keeps its own bespoke repositories,
// shadow relocations, resource filtering, and build metadata below — they
// diverge too far from the other plugins to share.

repositories {
    // mavenLocal is opt-in (-PuseMavenLocal) so normal/CI builds resolve
    // hibernia-framework only from the declared remotes — reproducible, never a
    // stale local artifact. Pass the flag (added first) when iterating on the
    // hibernia-framework submodule locally. (PAR-267)
    if (providers.gradleProperty("useMavenLocal").isPresent) {
        mavenLocal()
    }
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://hub.spigotmc.org/nexus/content/groups/public")
    maven("https://maven.enginehub.org/repo/")
    maven("https://repo.codemc.org/repository/maven-public/")
    maven("https://ci.ender.zone/plugin/repository/everything/")
    maven("https://nexus.hc.to/content/repositories/pub_releases/")
    maven("https://repo.minebench.de/")
    maven("https://jitpack.io")
    maven("https://ci.nyaacat.com/maven/")
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
    maven("https://repo.paradaux.io/releases")
    maven("https://repo.paradaux.io/snapshots")
    maven("https://repo.nexomc.com/releases") // Nexo (custom-item integration)
}
// plugin.yml's ${bukkit.plugin.version} token resolves to the plain monorepo
// version. (The old Jenkins/manual build-number + timestamp suffix was removed.)
val pluginVersion = project.version.toString()

// Compiled against the Paper API (a superset of spigot-api) so the folded-in
// version adapters' Paper-only snapshot API (BlockDestroyEvent, getState(boolean),
// getHolder(boolean)) resolves. ChestShop runs on one modern server version, so
// the old 1.13.2 multi-version-adapter baseline is gone. The Paper version is
// pinned centrally in gradle/libs.versions.toml (libs.paper.api).

// Maven's `provided` scope is visible to tests; Gradle's `compileOnly` is not.
// Make the provided APIs visible to test *compilation*, but NOT to the test
// runtime wholesale — dumping the soft-deps' full closures onto the runtime classpath
// pulls a second Bukkit API (via worldguard) that collides with spigot-api and
// breaks Bukkit-backed static initializers (MaterialUtil) under test. The
// server API and selected non-transitive editing API jars are added below; a
// separate startup task also verifies the optional editing APIs are absent.
configurations.testCompileOnly.get().extendsFrom(configurations.compileOnly.get())

// EngineHub moved its toolchain to Java 25, so anything they publish from now on
// is unusable on our Java 21 toolchain. Pin both lines to the last Java-21
// artifacts and never track a moving snapshot: WorldEdit at 7.3.9 (the 8.0.0
// -SNAPSHOT WorldGuard drags in is Java 25), WorldGuard at 7.0.17 (7.0.18 and the
// 7.1.0-SNAPSHOT it used to resolve are both Java 25). (PAR-330)
configurations.configureEach {
    resolutionStrategy {
        force(
            "com.sk89q.worldedit:worldedit-core:7.3.9",
            "com.sk89q.worldedit:worldedit-bukkit:7.3.9",
            "com.sk89q.worldguard:worldguard-core:7.0.17",
            "com.sk89q.worldguard:worldguard-bukkit:7.0.17",
        )
    }
}

dependencies {
    // --- server API: keep transitive (Bukkit compilation needs its deps:
    //     configurate/yaml/guava/etc.).
    compileOnly(libs.paper.api)
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")

    // Lombok — @Getter on the framework @ConfigurationComponent (matches treasury/business).
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    // --- soft-depend plugin APIs: compiled against, never shaded, and resolved
    //     NON-TRANSITIVELY. We reference each plugin's own classes only; pulling
    //     their full dependency closures (as Gradle's compileOnly does, unlike
    //     Maven's provided scope) drags in dead/incompatible artifacts — e.g.
    //     RedProtect -> de.keyle:mypet (gone), worldguard -> worldedit 8.0.0
    //     -SNAPSHOT (needs Java 25). Add a specific transitive back explicitly
    //     below only if compilation actually requires it.
    // WorldGuard integration genuinely needs WorldGuard's transitives (StateFlag,
    // Flags, RegionPermissionModel) + WorldEdit's BukkitAdapter, so these stay
    // transitive — but WorldEdit is force-pinned to 7.3.9 below (the snapshot
    // chain otherwise resolves worldedit 8.0.0-SNAPSHOT, which needs Java 25).
    compileOnly("com.sk89q.worldedit:worldedit-core:7.3.9") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.9") { isTransitive = false }
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Core:2.16.1") { isTransitive = false }
    // WorldGuard soft-depend: pull only its own API jars, non-transitively, so
    // none of its server-provided transitives (bukkit/gson/fastutil/guava) fight
    // paper-api. They carry the classes we touch (WorldGuard, StateFlag, Flags,
    // RegionPermissionModel, WorldGuardPlugin). Pinned to a release, not
    // 7.1.0-SNAPSHOT — that snapshot moved to Java 25 on 2026-08-01 and broke the
    // build with no commit of ours involved. (PAR-330)
    compileOnly("com.sk89q.worldguard:worldguard-core:7.0.17") { isTransitive = false }
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.17") { isTransitive = false }
    compileOnly("com.github.TechFortress:GriefPrevention:16.12.0") { isTransitive = false }
    compileOnly("com.nexomc:nexo:1.15.0") { isTransitive = false }
    // Direct project dependencies on the in-repo API modules (ADT stale-chestshop-substitution-comment):
    // this is a single Gradle build (settings.gradle.kts include(...)), NOT a composite with
    // includeBuild substitution, so there is no version pin to manage. compileOnly because
    // Treasury/Business provide the API classes at runtime (see softdepend in plugin.yml);
    // non-transitive so the APIs' own deps don't leak onto ChestShop's compile classpath.
    compileOnly(project(":treasury:treasury-api")) { isTransitive = false }
    compileOnly(project(":business:business-api")) { isTransitive = false }

    // --- HiberniaFramework: Guice DI, the annotation-driven Configurator,
    //     Commander, and i18n. Bundled (and relocated below) like treasury/business.
    implementation(libs.hibernia.framework)
    implementation(libs.guice)
    implementation(libs.reflections)

    // --- bundled libraries (relocated + shaded into the plugin jar below).
    // `api` (rather than implementation) is harmless now that everything is one
    // module; kept so they stay on the runtime classpath that shadowJar bundles.
    // MyBatis (annotation-SQL persistence) over the existing SQLite database files —
    // the same service→mapper layer the other plugins use, just not against MariaDB
    // (PAR-282). The SQLite JDBC driver (org.sqlite.JDBC) is provided by the server at
    // runtime, as it was for the old ORMlite layer.
    implementation(libs.mybatis.core)

    // Adventure is provided natively by Paper — NOT bundled or relocated (the old
    // bundled+relocated Adventure, plus the de.themoep MineDown/lang libraries, were
    // removed: MineDown's internals implemented a now-sealed Adventure interface and
    // broke at runtime. Messaging now uses the framework i18n + messages.properties).
    // ChestShop compiles against the serializers it uses directly; Paper supplies them.
    compileOnly("net.kyori:adventure-text-serializer-gson:4.21.0")
    compileOnly("net.kyori:adventure-text-serializer-legacy:4.21.0")

    // --- tests
    // Server API on the test runtime (tests mock/instantiate org.bukkit.* types).
    testImplementation(libs.paper.api)
    // Only EngineHub API jars, never their transitive Bukkit implementations.
    testRuntimeOnly("com.sk89q.worldedit:worldedit-core:7.3.9") { isTransitive = false }
    testRuntimeOnly("com.sk89q.worldguard:worldguard-core:7.0.17") { isTransitive = false }
    testRuntimeOnly("com.sk89q.worldedit.worldedit-libs:core:7.3.9") { isTransitive = false }
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
    testImplementation(libs.assertj.core)
    // MockBukkit — a real in-memory server so Bukkit-coupled services are exercised for
    // real (ItemStacks, inventories, signs) rather than mock-verified.
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.116.3")

    // Shared startup + message-key test-kit (the startup + messages.properties audit).
    // Brings JUnit, Guice, the framework and MockBukkit transitively (declared `api`
    // there), matching the coordinate/version already used above.
    testImplementation(project(":test-support"))

    // SQLite JDBC driver — provided by the server at runtime in production (see the
    // DatabaseModule note above), so it's absent from the plugin's own deps. The startup
    // test builds the real SQLite-backed DatabaseModule (it runs schema DDL at wiring
    // time), so the driver must be on the test runtime classpath.
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.53.4.0")
    // The in-repo Treasury/Business API classes are on the test *compile* classpath already
    // (testCompileOnly extendsFrom compileOnly, above); add them to the test *runtime* so the
    // market/account services (which mock TreasuryApi/MarketApi/ShopQueryApi/BusinessApi and
    // build their DTOs) can actually be loaded and exercised for coverage. Non-transitive, so
    // this does NOT drag the soft-deps' closures (worldguard's second Bukkit API) that the note
    // above deliberately keeps off the runtime — these modules' own deps are all compileOnly.
    testRuntimeOnly(project(":treasury:treasury-api")) { isTransitive = false }
    testRuntimeOnly(project(":business:business-api")) { isTransitive = false }
}

// plugin.yml carries ${bukkit.plugin.version}; substitute the plain project
// version. Literal token replace (not Groovy expand) to avoid touching the
// MiniMessage content in messages.properties.
tasks.processResources {
    filteringCharset = "UTF-8"
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("plugin.yml") {
        filter { line -> line.replace("\${bukkit.plugin.version}", pluginVersion) }
    }
    // LICENSE shipped at the jar root.
    from(projectDir) { include("LICENSE") }
}

tasks.test {
    useJUnitPlatform()
    // Mockito's inline mock maker attaches its agent dynamically on JDK 21+.
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}

// Coverage targets apply to the in-scope set — the service/logic layer (services, model,
// utils). Bukkit/framework glue that can't be exercised without a live server or soft-dep
// plugins is excluded, matching the treasury/business standard: the plugin main, command &
// listener entrypoints, Usher dialog UI, the soft-depend integrations (WorldGuard/GriefPrevention/
// Nexo), the Guice module, and the MyBatis mappers.
val jacocoExcludes = listOf(
    // The legacy ABI adapters are event/facade glue, like native integrations.
    "com/Acrobot/ChestShop/**",
    "io/paradaux/chestshop/ChestShop.class",
    "io/paradaux/chestshop/ChestShop\$*.class",
    "io/paradaux/chestshop/listeners/**",
    "io/paradaux/chestshop/commands/**",
    "io/paradaux/chestshop/dialogs/**",
    "io/paradaux/chestshop/integration/**",
    "io/paradaux/chestshop/guice/**",
    "io/paradaux/chestshop/mappers/**",
)

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    classDirectories.setFrom(
        files(classDirectories.files.map { fileTree(it) { exclude(jacocoExcludes) } }),
    )
}

// A ratchet, not a target. This gate used to sit at 0.99/0.99, which had grown
// tests that only exist to execute lines (getter round-trips, "enum has 3
// values", *CoverageTest/*ExtraTest classes); those were removed and coverage
// did not move — 99.5% line / 98.7% branch (2026-10) — so it measured nothing
// the behavioural tests don't. The gate now sits a few points under that, so a
// change that drops real tests still fails, and whether the tests would catch a
// bug is measured by mutation testing (`pitest`, below) instead.
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    classDirectories.setFrom(
        files(classDirectories.files.map { fileTree(it) { exclude(jacocoExcludes) } }),
    )
    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.97".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.96".toBigDecimal()
            }
        }
    }
}

tasks.test { finalizedBy(tasks.jacocoTestReport) }
tasks.named("check") { dependsOn(tasks.jacocoTestCoverageVerification) }

// Mutation testing (`./gradlew :chestshop:pitest`; not part of `check`). Scoped to
// the trade + pricing core — price/quantity parsing, partial-fill maths, the trade
// validator/settlement pipeline and the economy bridge — where a surviving mutant
// is a mis-priced or double-settled trade. Sign/inventory/UI plumbing stays under
// the jacoco ratchet only.
pitest {
    targetClasses.set(
        listOf(
            "io.paradaux.chestshop.utils.PriceUtil*",
            "io.paradaux.chestshop.utils.QuantityUtil*",
            "io.paradaux.chestshop.utils.NumberUtil*",
            "io.paradaux.chestshop.services.impl.PartialFillCalculatorImpl*",
            "io.paradaux.chestshop.services.impl.TradeValidatorImpl*",
            "io.paradaux.chestshop.services.impl.TradeSettlementImpl*",
            "io.paradaux.chestshop.services.impl.TradeContextFactoryImpl*",
            "io.paradaux.chestshop.services.impl.TradeRecoveryService*",
            "io.paradaux.chestshop.services.impl.EconomyServiceImpl*",
            "io.paradaux.chestshop.services.impl.GoodsTransferImpl*",
            "io.paradaux.chestshop.services.impl.TransactionServiceImpl*",
        ),
    )
    // Ratchet a couple of points under the measured scores (2026-10: 88.7%
    // mutation score, 99% line coverage of the mutated classes; 80.4% before the
    // partial-fill tests). Raise as survivors are killed.
    mutationThreshold.set(86)
    coverageThreshold.set(97)
    // FaweShopCleanupTest only runs in faweTest, on FAWE's own WorldEdit API (the
    // plain test classpath has stock WorldEdit, so it fails there); none of the
    // mutated classes depend on it.
    excludedTestClasses.set(
        listOf(
            "io.paradaux.chestshop.listeners.FaweShopCleanupTest",
            "io.paradaux.chestshop.integration.InstalledAddonCompatibilityTest",
        ),
    )
}

// =====================================================================
// Shaded chestshop-<version>.jar (formerly produced by the separate :assemble module).
// =====================================================================

// Shadow 9 + plain :jar both target build/libs/<name>.jar; disable :jar so the
// shaded plugin jar survives (see workspace CLAUDE.md rule 8).
tasks.jar { enabled = false }

tasks.shadowJar {
    // -> build/libs/chestshop-<version>.jar, standardised with the other plugins:
    // base name defaults to the project name ("chestshop") and version to the
    // pinned monorepo version from the root allprojects block (2.3.0-SNAPSHOT or
    // -Pversion). Only the "-all" classifier is dropped. (PAR-274)
    archiveClassifier.set("")

    // Bundle every runtime (api/implementation) dependency. The server API and
    // the soft-depend plugin APIs are compileOnly, so they never reach the
    // runtime classpath and are never shaded. (Replaced the old include-whitelist
    // when HiberniaFramework + Guice were added — enumerating Guice's transitives
    // in a whitelist is fragile; this mirrors treasury/business.)

    // Relocate shaded libraries so they don't clash with the server or other plugins.
    relocate("com.google.inject", "io.paradaux.chestshop.Libs.guice")
    relocate("javax.inject", "io.paradaux.chestshop.Libs.javaxinject")
    relocate("org.aopalliance", "io.paradaux.chestshop.Libs.aopalliance")

    mergeServiceFiles()

    // Top-level docs at the jar root.
    from(projectDir) { include("README.md", "SECURITY.md") }

    manifest {
        // Tells Paper the plugin is compiled against Mojang mappings (required).
        attributes("paperweight-mappings-namespace" to "mojang")
    }
}

// Building the module produces the shaded jar.
tasks.assemble { dependsOn(tasks.shadowJar) }

// =====================================================================
// Dev-server staging. ChestShop can't apply io.paradaux.paper-server-conventions
// (its repos/shadow/resources diverge), so it carries its own copyPlugin that
// mirrors the convention: stage the shaded jar into server/plugins after every
// shadowJar. Pass -Pci=true to skip (CI, or to avoid disturbing a running dev
// server). A top-level subproject is one level under the root → "../server/plugins".
// =====================================================================
val pluginsDir = layout.projectDirectory.dir("../server/plugins")
val copyPlugin = tasks.register<Copy>("copyPlugin") {
    dependsOn(tasks.shadowJar)
    from(tasks.shadowJar.flatMap { it.archiveFile })
    into(pluginsDir)
    onlyIf { !project.hasProperty("ci") } // don't run on CI
    doFirst {
        // Remove any previously-staged ChestShop jar so the dev server never loads
        // two copies of the same plugin: the current chestshop-<version>.jar and
        // the legacy ChestShop.jar (case-insensitive — it predates the standardised
        // name from PAR-274).
        val re = Regex("^chestshop(-\\d.*)?\\.jar$", RegexOption.IGNORE_CASE)
        pluginsDir.asFile.listFiles { f -> f.isFile && re.matches(f.name) }?.forEach { it.delete() }
    }
}
tasks.shadowJar { finalizedBy(copyPlugin) }

// Exercise each optional editing API on its own runtime: FAWE replaces WorldEdit.
val faweTestLibraries = configurations.create("faweTestLibraries")
dependencies {
    faweTestLibraries("com.fastasyncworldedit:FastAsyncWorldEdit-Core:2.16.1") { isTransitive = false }
    faweTestLibraries("com.fastasyncworldedit:FastAsyncWorldEdit-Libs-Core:2.16.1") { isTransitive = false }
}
tasks.test { exclude("**/FaweShopCleanupTest*") }
val faweTest = tasks.register<Test>("faweTest") {
    description = "Test the FAWE completed-chunk adapter on FAWE's own WorldEdit API."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = faweTestLibraries + sourceSets.test.get().runtimeClasspath.filter {
        !it.name.startsWith("worldedit-core-")
    }
    include("**/FaweShopCleanupTest*")
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}
tasks.named("check") { dependsOn(faweTest) }

val testWithoutWorldEditingApis = tasks.register<Test>("testWithoutWorldEditingApis") {
    description = "Verify the real plugin injector starts without WorldEdit, FAWE or WorldGuard APIs."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath.filter {
        !it.name.startsWith("worldedit-") && !it.name.startsWith("worldguard-") &&
            it.name != "core-7.3.9.jar"
    }
    include("**/ChestShopStartupTest*")
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}
tasks.named("check") { dependsOn(testWithoutWorldEditingApis) }

// Optional acceptance probe against an operator's real add-on jars. They are never bundled.
// ./gradlew :chestshop:installedAddonTest -PlegacyAddonDir=/path/to/server/plugins
val installedAddonTest = tasks.register<Test>("installedAddonTest") {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/InstalledAddonCompatibilityTest*")
    useJUnitPlatform()
    val addonDir = providers.gradleProperty("legacyAddonDir")
    onlyIf { addonDir.isPresent }
    if (addonDir.isPresent) systemProperty("legacyAddonDir", addonDir.get())
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}
tasks.test { exclude("**/InstalledAddonCompatibilityTest*") }
