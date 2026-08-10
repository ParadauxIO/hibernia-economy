plugins {
    `java-library`
    `maven-publish`
    jacoco
    id("io.paradaux.jvm-conventions") // Java 21 toolchain + UTF-8 / release=21 JavaCompile
    id("io.paradaux.published-library-conventions") // publish target: repo.paradaux.io + REPO_USER/REPO_PASS
}

group = "io.paradaux"
version = rootProject.version
description = "Business API"

java {
    withSourcesJar()
    withJavadocJar() // publish a -javadoc artifact for this documented public API (ADT no-javadoc-jar-or-compat-policy)
}

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
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

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.hibernia.framework)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    // @Nullable on the published API surface (ADT firmplayer-null-uuid-contract-break).
    compileOnly(libs.jetbrains.annotations)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj.core)
    // FirmPlayer implements HiberniaPlayer (compileOnly above); the test
    // instantiates it, so the interface must be on the test classpath.
    testImplementation(libs.hibernia.framework)
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            groupId = project.group.toString()
            artifactId = "business-api"
            version = project.version.toString()

            pom {
                name.set("business-api")
                description.set("Public API for the DemocracyCraft Business plugin.")
                url.set("https://repo.paradaux.io")
                licenses {
                    license {
                        name.set("AGPL-3.0-or-later")
                        url.set("https://www.gnu.org/licenses/agpl-3.0.en.html")
                        distribution.set("repo")
                    }
                }
                developers { developer { id.set("rian"); name.set("Rían Errity") } }
                scm {
                    url.set("https://github.com/DemocracyCraft/Business")
                    connection.set("scm:git:https://github.com/DemocracyCraft/Business.git")
                    developerConnection.set("scm:git:ssh://git@github.com/DemocracyCraft/Business.git")
                }
            }
        }
    }
}
