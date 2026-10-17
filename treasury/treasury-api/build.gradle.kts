plugins {
    `java-library`
    `maven-publish`
    id("io.paradaux.jvm-conventions") // Java 21 toolchain + UTF-8 / release=21 JavaCompile
    id("io.paradaux.published-library-conventions") // publish target: repo.paradaux.io + REPO_USER/REPO_PASS
}

group = "io.paradaux"
version = rootProject.version

java {
    withSourcesJar()
    withJavadocJar() // publish a -javadoc artifact for this documented public API (ADT no-javadoc-jar-published)
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.withType<Javadoc> {
    options.encoding = "UTF-8"
    // Lombok generates builder()/getters at compile time, but javadoc runs on the
    // pre-Lombok source, so {@link}s to those members resolve as "reference not
    // found". Don't let that fail the published -javadoc jar (and thus the publish
    // workflow) — emit them as warnings instead (CI fix).
    isFailOnError = false
}

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    compileOnly(libs.jetbrains.annotations)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj.core)
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            groupId = project.group.toString()
            artifactId = "treasury-api"
            version = project.version.toString()

            pom {
                name.set("treasury-api")
                description.set("Public API for the DemocracyCraft Treasury economy plugin.")
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
                    url.set("https://github.com/DemocracyCraft/Treasury")
                    connection.set("scm:git:https://github.com/DemocracyCraft/Treasury.git")
                    developerConnection.set("scm:git:ssh://git@github.com/DemocracyCraft/Treasury.git")
                }
            }
        }
    }
}
