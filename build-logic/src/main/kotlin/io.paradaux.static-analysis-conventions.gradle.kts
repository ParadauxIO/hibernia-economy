import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

// =============================================================================
// io.paradaux.static-analysis-conventions — compile-time static analysis and
// formatting for every JVM project. Applied by io.paradaux.jvm-conventions (so
// every module that takes the JVM baseline gets it for free) and by the root
// project (which only formats the repo-level files — see the Spotless section).
//
//   * Error Prone   runs inside javac on main AND test sources. Its default
//                   ERROR checks fail the build; a curated set of WARNING checks
//                   is promoted to ERROR below. Everything else stays a warning.
//   * NullAway      an Error Prone plugin, ERROR, main sources only, scoped per
//                   project to the packages listed in `nullAwayScope`.
//   * Spotless      palantir-java-format for Java, ktlint for *.gradle.kts,
//                   whitespace hygiene for md/yml/sql. Ratcheted against
//                   origin/develop so only files a branch touches are enforced.
//
// Suppressing a finding: @SuppressWarnings("CheckName") on the narrowest element,
// with a one-line comment saying why the check is wrong there. See
// wiki/build-and-test.md#static-analysis.
// =============================================================================

plugins {
    id("net.ltgt.errorprone")
    id("com.diffplug.spotless")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

// Spotless hooks spotlessCheck into `check` wherever the base plugin is applied.
// The JVM projects have it via java; the root has no source, so give it `base`
// purely so `./gradlew check` covers the root-owned files too.
if (project == rootProject) {
    pluginManager.apply("base")
    // The root declares no repositories of its own; Spotless resolves ktlint here.
    repositories { mavenCentral() }
}

// -----------------------------------------------------------------------------
// Error Prone + NullAway
// -----------------------------------------------------------------------------

// WARNING-level checks promoted to ERROR. Each was evaluated against this
// codebase and its findings fixed; they're the ones whose warnings were (or would
// be) real bugs here: money is BigDecimal, the servers' JVM locale isn't ours to
// pick, and the plugins are full of fire-and-forget async work.
//
// Deliberately left as warnings: ReferenceEquality (every hit was an intended
// identity check — ItemMeta fast paths, mock equals answers), JavaTimeDefaultTimeZone
// (the real fixes need DB-side NOW(), not a cosmetic ZoneId.systemDefault()),
// JdkObsolete (LinkedList perf nits), EmptyCatch, and the javadoc/style checks.
val promotedChecks = listOf(
    // Money: BigDecimal.equals compares scale (-1 != -1.00) and new BigDecimal(0.1)
    // captures the binary double, not 0.1.
    "BigDecimalEquals",
    "BigDecimalLiteralDouble",
    // A switch over an enum that silently ignores a new constant (AccountType etc.).
    "MissingCasesInEnumSwitch",
    // Default locale/charset leaking into behaviour: on a tr_TR JVM "daily".toUpperCase()
    // is "DAİLY", so TaxCycleType.valueOf and base-36 shop ids broke.
    "StringCaseLocaleUsage",
    "DefaultCharset",
    // A dropped Future swallows its exception, so async failures vanish.
    "FutureReturnValueIgnored",
    // printStackTrace bypasses the plugin/Spring logger.
    "CatchAndPrintStackTrace",
    // `a && b || c` — found ChestShop's SHOW_MESSAGE_FULL_SHOP=false being ignored.
    "OperatorPrecedence",
    // Dead fields/locals/methods are usually a half-finished refactor (unused
    // injected deps, counters a test never asserts on).
    "UnusedVariable",
    "UnusedMethod",
)

// NullAway scope: project path -> the package prefixes NullAway treats as
// annotated (null-marked) in that project's main source set. Unlisted projects
// compile with NullAway off. Grow this list a module at a time; @Nullable is the
// org.jetbrains.annotations one the published APIs already use (NullAway accepts
// any annotation named @Nullable).
val nullAwayScope = mapOf(
    ":common" to "io.paradaux.common",
    ":test-support" to "io.paradaux.hibernia.testsupport",
    ":treasury:treasury-api" to "io.paradaux.treasury",
    ":business:business-api" to "io.paradaux.business",
)

plugins.withId("java") {
    dependencies {
        "errorprone"(catalog.findLibrary("errorprone-core").get())
        "errorprone"(catalog.findLibrary("nullaway").get())
        // ArchUnit for the per-module ArchitectureTest layering rules. Declared here
        // rather than in each module so adding a rule never touches a build file.
        "testImplementation"(catalog.findLibrary("archunit").get())
    }

    val nullAwayPackages = nullAwayScope[path]

    tasks.withType<JavaCompile>().configureEach {
        options.errorprone {
            // Annotation-processor output (build/generated) isn't code we can fix
            // by hand. Lombok rewrites the AST in place rather than generating
            // files, and produced no findings of its own.
            disableWarningsInGeneratedCode.set(true)
            excludedPaths.set(".*/build/generated/.*")
            promotedChecks.forEach { error(it) }
            // Bukkit calls @EventHandler methods reflectively, and their parameter
            // is how the event type is subscribed, so neither is "unused".
            option("UnusedMethod:ExemptingMethodAnnotations", "org.bukkit.event.EventHandler")
            option("Unused:methodAnnotationsExemptingParameters", "org.bukkit.event.EventHandler")

            if (name == "compileJava" && nullAwayPackages != null) {
                check("NullAway", CheckSeverity.ERROR)
                option("NullAway:AnnotatedPackages", nullAwayPackages)
                // Lombok no-arg beans are populated reflectively by MyBatis/Jackson
                // after construction, so "field not initialised by the constructor"
                // is noise there. Nullable columns still need @Nullable on the field.
                option("NullAway:ExternalInitAnnotations", "lombok.NoArgsConstructor,lombok.Data")
            } else {
                // Tests and not-yet-annotated modules: NullAway off.
                disable("NullAway")
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Spotless (ratcheted)
// -----------------------------------------------------------------------------

// Only files that differ from this ref are checked/formatted, so adopting the
// formatter didn't need a whole-repo reformat that would conflict with every open
// branch. Override with -PspotlessRatchetFrom=<ref> (e.g. origin/main).
//
// The ref must exist locally: CI has to check out with full history
// (actions/checkout `fetch-depth: 0`) or `git fetch origin develop`. When it's
// missing, Spotless is not configured at all (spotlessApply on an unratcheted
// tree would reformat the whole repo); spotlessCheck then fails on CI
// (CI=true) so a shallow checkout can't silently skip formatting, and is a
// warning-only no-op locally.
val ratchetRef = providers.gradleProperty("spotlessRatchetFrom").orElse("origin/develop").get()
// runCatching: no git binary / not a checkout (e.g. a source tarball) counts as missing.
val ratchetRefExists = runCatching {
    providers.exec {
        commandLine("git", "rev-parse", "--verify", "--quiet", "$ratchetRef^{commit}")
        workingDir = rootDir
        isIgnoreExitValue = true
    }.result.get().exitValue == 0
}.getOrDefault(false)

if (ratchetRefExists) {
    spotless {
        ratchetFrom(ratchetRef)

        if (project == rootProject) {
            // The root owns every build script (incl. build-logic and the separate
            // integration-tests build) and the repo's prose/config/SQL, so each
            // file is checked exactly once.
            val excluded = listOf(
                "**/build/**", "**/.gradle/**", "**/node_modules/**", "**/.next/**",
                "hibernia-framework/**", "realty/**", "economy-explorer/**",
                "server/**", "release/**", ".claude/**",
            )
            kotlinGradle {
                target("**/*.gradle.kts")
                targetExclude(excluded)
                // intellij_idea rather than ktlint_official: measured against the
                // existing scripts it rewrites 9 files (+73/-46) instead of 13
                // (+288/-201) — it matches how the build files are already written.
                ktlint(catalog.findVersion("ktlint").get().requiredVersion)
                    .editorConfigOverride(mapOf("ktlint_code_style" to "intellij_idea"))
            }
            format("misc") {
                target("**/*.md", "**/*.yml", "**/*.yaml", "**/*.sql")
                targetExclude(excluded)
                trimTrailingWhitespace()
                endWithNewline()
            }
        }

        // palantir-java-format over google-java-format (AOSP): measured over the
        // whole tree, palantir rewrites 696 files (+15.9k/-10.6k lines), AOSP 790
        // files (+28.9k/-17.7k). Palantir's lambda/chain wrapping is the closer fit.
        plugins.withId("java") {
            java {
                palantirJavaFormat(catalog.findVersion("palantir-java-format").get().requiredVersion)
                removeUnusedImports()
                trimTrailingWhitespace()
                endWithNewline()
            }
        }
    }
} else {
    spotless { isEnforceCheck = false }
    val onCi = providers.environmentVariable("CI").map { it.toBoolean() }.getOrElse(false)
    if (project == rootProject) {
        logger.warn(
            "Spotless: ratchet ref '$ratchetRef' not found — formatting checks skipped. " +
                "Run `git fetch origin develop` (CI: checkout with fetch-depth: 0).",
        )
    }
    if (onCi) {
        // Fail check/spotlessCheck so a shallow CI checkout is loud, not silent.
        val missingRef = ratchetRef
        tasks.matching { it.name == "check" || it.name == "spotlessCheck" }.configureEach {
            doFirst {
                throw GradleException(
                    "Spotless ratchet ref '$missingRef' is missing; check out with fetch-depth: 0 " +
                        "or fetch it before running check.",
                )
            }
        }
    }
}
