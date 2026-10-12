rootProject.name = "integration-tests"

include("mock-client", "tests", "diag-plugin", "nexo-stub")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // MCProtocolLib SNAPSHOTs (1.21.11-SNAPSHOT lives here).
        maven("https://repo.opencollab.dev/maven-snapshots") {
            name = "opencollab-snapshots"
            mavenContent { snapshotsOnly() }
        }
        // CloudburstMC math + NukkitX fastutil — transitive deps of MCProtocolLib
        // that are not published to Maven Central.
        maven("https://repo.opencollab.dev/maven-releases") {
            name = "opencollab-releases"
            mavenContent { releasesOnly() }
        }
        // Paper API — for diag-plugin
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
        }
        // WorldGuard/WorldEdit API — for diag-plugin's region-creation command
        maven("https://maven.enginehub.org/repo/") {
            name = "enginehub"
        }
    }
}
