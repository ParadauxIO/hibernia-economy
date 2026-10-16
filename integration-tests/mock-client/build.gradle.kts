plugins {
    `java-library`
    `maven-publish`
}

description = "Standalone Minecraft mock-player client for end-to-end integration testing of Paper plugins."

dependencies {
    // Public — consumers see the MCProtocolLib types if they want to drop down
    // to raw packet handling. Keep this `api` so the transitive surface is
    // explicit; downgrade to `implementation` if we ever stabilise a wrapper-
    // only API and want to hide the underlying library.
    api("org.geysermc.mcprotocollib:protocol:1.21.11-SNAPSHOT")

    // ClientboundSystemChatPacket exposes content as Adventure Component;
    // the plain-text serializer turns it into something we can match on.
    // adventure-api is already on the classpath transitively from MCProtocolLib.
    implementation("net.kyori:adventure-text-serializer-plain:4.17.0")

    // Logging facade only — consumers bring their own backend (logback,
    // log4j2, etc.). The tests submodule wires logback for its runs.
    implementation("org.slf4j:slf4j-api:2.0.20")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.6.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set("mock-client")
                description.set(project.description)
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }
    // Publish target intentionally left out — consumers can configure their
    // own repository in a publishing override.
}
