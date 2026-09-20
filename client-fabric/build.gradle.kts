plugins {
    id("fabric-loom") version "1.17.21"
}

group = "io.github.bearisbug.synaxis.game"

// The mod version is what players see in the mod list, so it stays SemVer; the engine's own
// version scheme (`dev` unless GAME_ENGINE_VERSION says otherwise) is what :protocol is published
// under and is read separately below.
version = System.getenv("CLIENT_MOD_VERSION") ?: "0.1.0"

val minecraftVersion = "26.2"
val loaderVersion = "0.19.5"
val fabricApiVersion = "0.161.0+26.2"
val protocolVersion = System.getenv("GAME_ENGINE_VERSION") ?: "dev"

repositories {
    // :protocol comes from the engine build's publishToMavenLocal (ADR-M2-015).
    mavenLocal()
    mavenCentral()
}

dependencies {
    // 26.2 ships unobfuscated: Mojang publishes no mappings file for it and there is no yarn build,
    // so the mod is written against Minecraft's own names. Intermediary 0.0.0 is the empty tiny
    // file Fabric publishes for that era; Loom still wants something in the `mappings` configuration.
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:intermediary:0.0.0:v2")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // One codec for both ends (§24). `include` nests it in the mod jar, so the mod ships the very
    // artifact the server was built against.
    implementation("io.github.bearisbug.synaxis:protocol:$protocolVersion")
    include("io.github.bearisbug.synaxis:protocol:$protocolVersion")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.processResources {
    inputs.property("version", version)
    inputs.property("minecraftVersion", minecraftVersion)
    inputs.property("loaderVersion", loaderVersion)

    filesMatching("fabric.mod.json") {
        expand(
            "version" to version,
            "minecraftVersion" to minecraftVersion,
            "loaderVersion" to loaderVersion,
        )
    }
}
