// =============================================================================
// io.paradaux.published-library-conventions — the shared publish TARGET for the
// two library modules we publish to repo.paradaux.io (treasury-api, business-api).
//
// It declares ONLY the `publishing { repositories { maven { … } } }` block — the
// snapshot-vs-release URL selection and the REPO_USER / REPO_PASS env credentials
// — which was previously duplicated byte-for-byte in treasury/build.gradle.kts and
// business/build.gradle.kts (global/build/0004). The publications themselves stay
// in each module's own build.gradle.kts; only the publish repository moves here.
//
// Applies maven-publish so the extension exists; a module that applies this plugin
// still defines its own publication(s).
// =============================================================================

plugins {
    `maven-publish`
}

publishing {
    repositories {
        maven {
            val isSnapshot = project.version.toString().endsWith("-SNAPSHOT")

            name = if (isSnapshot) "Snapshots" else "Releases"
            url = uri(
                if (isSnapshot)
                    "https://repo.paradaux.io/snapshots"
                else
                    "https://repo.paradaux.io/releases"
            )

            credentials {
                username = System.getenv("REPO_USER")
                password = System.getenv("REPO_PASS")
            }
        }
    }
}
