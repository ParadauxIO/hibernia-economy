plugins {
    java
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.206-stable")
}

// Builds "Nexo.jar" — a minimal stand-in for the Nexo plugin that provides just the
// com.nexomc.nexo API surface ChestShop's integration calls, plus a couple of defined
// test items and RCON-drivable commands. Lets the harness validate ChestShop's native
// Nexo integration (PAR-291) without the paid Nexo plugin. It registers under the plugin
// name "Nexo" so ChestShop's softdepend hook fires.
tasks.jar {
    archiveBaseName.set("Nexo")
    archiveVersion.set("")
    archiveClassifier.set("")
}
